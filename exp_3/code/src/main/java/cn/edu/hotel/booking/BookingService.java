package cn.edu.hotel.booking;

import cn.edu.hotel.catalog.Pricing;
import cn.edu.hotel.core.Audit;
import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.FaultProbe;
import cn.edu.hotel.core.Idempotency;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.core.Json;
import cn.edu.hotel.identity.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BookingService {
  private final Db db;
  private final Pricing pricing;
  private final BusinessClock clock;
  private final Audit audit;
  private final Idempotency keys;
  private final Json json;
  private final FaultProbe faults;

  public BookingService(
      Db db,
      Pricing pricing,
      BusinessClock clock,
      Audit audit,
      Idempotency keys,
      Json json,
      FaultProbe faults) {
    this.db = db;
    this.pricing = pricing;
    this.clock = clock;
    this.audit = audit;
    this.keys = keys;
    this.json = json;
    this.faults = faults;
  }

  public Map<String, Object> accessible(Actor actor, long id) {
    String scope;
    Object owner;
    if (actor.is("CUSTOMER")) {
      scope = " AND customer_id=?";
      owner = actor.id();
    } else if (actor.is("STAFF")) {
      scope = " AND hotel_id=?";
      owner = actor.hotelId();
    } else {
      scope = " AND ?=1";
      owner = 1;
    }
    return db.one("SELECT * FROM booking WHERE id=?" + scope, id, owner);
  }

  public Map<String, Object> detail(Actor actor, long id) {
    Map<String, Object> order = accessible(actor, id);
    order.put(
        "hotelName",
        db.one("SELECT name FROM hotel WHERE id=?", order.get("hotel_id")).get("name"));
    order.put(
        "roomTypeName",
        db.one("SELECT name FROM room_type WHERE id=?", order.get("room_type_id")).get("name"));
    order.put(
        "nights", db.rows("SELECT * FROM booking_night WHERE booking_id=? ORDER BY stay_date", id));
    order.put("payments", db.rows("SELECT * FROM money_entry WHERE booking_id=? ORDER BY id", id));
    order.put(
        "stays",
        db.rows(
            "SELECT s.id,s.started_at,s.ended_at,r.number FROM stay s JOIN physical_room r ON"
                + " r.id=s.room_id WHERE s.booking_id=? ORDER BY r.number",
            id));
    order.put("refundable", refundable(order, clock.now()));
    order.put(
        "modifications",
        db.rows(
            "SELECT id,old_booking_id,new_booking_id,state,detail FROM modification WHERE"
                + " old_booking_id=? OR new_booking_id=?",
            id,
            id));
    order.put(
        "requests",
        db.rows(
            "SELECT id,kind,reason,status,resolution FROM reception_request WHERE booking_id=?"
                + " ORDER BY id DESC",
            id));
    return order;
  }

  public Map<String, Object> list(Actor actor, Map<String, String> query) {
    StringBuilder sql =
        new StringBuilder(
            " FROM booking b JOIN hotel h ON h.id=b.hotel_id JOIN room_type r ON"
                + " r.id=b.room_type_id WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (actor.is("CUSTOMER")) {
      sql.append(" AND b.customer_id=?");
      args.add(actor.id());
    }
    if (actor.is("STAFF")) {
      sql.append(" AND b.hotel_id=?");
      args.add(actor.hotelId());
    }
    String state = query.getOrDefault("status", "");
    if (!state.isBlank()) {
      sql.append(" AND b.status=?");
      args.add(state);
    }
    if (!query.getOrDefault("hotelId", "").isBlank()) {
      sql.append(" AND b.hotel_id=?");
      args.add(Input.id(query, "hotelId"));
    }
    if (!query.getOrDefault("contact", "").isBlank()) {
      sql.append(" AND b.contact ILIKE ?");
      args.add(
          "%" + Input.text(query, "contact", 60).replace("%", "\\%").replace("_", "\\_") + "%");
    }
    String column =
        switch (query.getOrDefault("dateType", "check_in")) {
          case "created_at" -> "(b.created_at AT TIME ZONE 'Asia/Shanghai')::date";
          case "paid_at" -> "(b.paid_at AT TIME ZONE 'Asia/Shanghai')::date";
          case "check_out" -> "b.check_out";
          default -> "b.check_in";
        };
    if (!query.getOrDefault("from", "").isBlank()) {
      sql.append(" AND ").append(column).append(">=?");
      args.add(Input.date(query, "from"));
    }
    if (!query.getOrDefault("to", "").isBlank()) {
      sql.append(" AND ").append(column).append("<=?");
      args.add(Input.date(query, "to"));
    }
    long count = db.jdbc.queryForObject("SELECT COUNT(*)" + sql, Long.class, args.toArray());
    long page = query.containsKey("page") ? Input.number(query, "page", 1, 100000) : 1;
    args.add((page - 1) * 30);
    return Map.of(
        "total",
        count,
        "page",
        page,
        "items",
        db.rows(
            "SELECT b.*,h.name AS hotel_name,r.name AS room_type_name"
                + sql
                + " ORDER BY b.id DESC LIMIT 30 OFFSET ?",
            args.toArray()));
  }

  public Map<String, Object> create(Actor actor, String key, Map<String, Object> body) {
    String quoteId = Input.text(body, "quoteId", 40);
    Map<String, Object> quoted = pricing.loadQuote(actor, quoteId);
    return keys.execute(
        actor,
        "create-booking",
        key,
        body,
        () -> {
          db.lockHotel(Db.value(quoted, "hotel_id"));
          return createLocked(actor, quoteId, body, null);
        });
  }

  private Map<String, Object> createLocked(
      Actor actor, String quoteId, Map<String, Object> body, Long parent) {
    Input.require(Input.flag(body, "accepted"), "请确认付款后仍需酒店接单及取消规则");
    Pricing.Calculation calculation = pricing.recheckQuote(actor, quoteId);
    Map<String, Object> quote = pricing.loadQuote(actor, quoteId);
    Map<String, Object> payload = json.object(quote.get("payload"));
    boolean offline = Input.flag(payload, "offline");
    boolean student = Input.flag(payload, "student");
    if (offline && student) {
      Input.require(Input.flag(body, "studentVerified"), "学生优惠需要前台确认校园卡核验");
    }
    String contact = Input.text(body, "contact", 60);
    String phone = Input.text(body, "phone", 30);
    Input.require(phone.matches("[+0-9 -]{6,30}"), "请填写有效联系电话");
    if (Input.flag(body, "simulateFailure")) {
      return Map.of("paymentFailed", true, "message", "模拟付款失败，没有创建订单或付款记录");
    }
    Instant now = clock.now();
    Pricing.Selection selection = calculation.selection();
    long id =
        db.insert(
            """
INSERT INTO booking(hotel_id,room_type_id,customer_id,channel,contact,phone,check_in,check_out,
  rooms,guest_count,status,amount,created_at,paid_at,deadline,parent_id,student_discount)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
            calculation.hotelId(),
            selection.roomType(),
            offline ? null : actor.id(),
            offline ? "OFFLINE" : "ONLINE",
            contact,
            phone,
            selection.start(),
            selection.end(),
            selection.rooms(),
            selection.guests(),
            offline ? "CONFIRMED" : "PENDING",
            calculation.total(),
            Db.time(now),
            Db.time(now),
            Db.time(now.plusSeconds(1800)),
            parent,
            student);
    for (Pricing.Night night : calculation.nights()) {
      db.jdbc.update(
          "INSERT INTO booking_night(booking_id,stay_date,unit_price,rooms,allocated)"
              + " VALUES(?,?,?,?,?)",
          id,
          night.day(),
          night.unitPrice(),
          selection.rooms(),
          offline);
    }
    faults.checkpoint("after-order-nights");
    db.jdbc.update(
        "INSERT INTO money_entry(booking_id,kind,amount,reason,created_at)"
            + " VALUES(?,'PAYMENT',?,'INITIAL',?)",
        id,
        calculation.total(),
        Db.time(now));
    db.jdbc.update("UPDATE quote SET used_booking_id=? WHERE id=?", id, pricing.uuid(quoteId));
    audit.record(
        actor,
        calculation.hotelId(),
        offline ? "OFFLINE_BOOKING" : "PAYMENT",
        "booking",
        id,
        "模拟付款 " + calculation.total() + " 分");
    if (offline) {
      sweepPending(calculation.hotelId());
    }
    return Map.of(
        "id", id, "status", offline ? "CONFIRMED" : "PENDING", "amount", calculation.total());
  }

  public Map<String, Object> confirm(Actor actor, long id, String key, Map<String, Object> body) {
    Map<String, Object> before = accessible(actor, id);
    actor.staff(Db.value(before, "hotel_id"));
    return keys.execute(
        actor,
        "confirm-" + id,
        key,
        body,
        () -> {
          db.lockHotel(Db.value(before, "hotel_id"));
          Map<String, Object> order = db.one("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
          version(order, body);
          requireState(order, "PENDING");
          if (!clock.now().isBefore(Db.instant(order, "deadline"))) {
            cancelLocked(null, order, "TIMEOUT", Db.value(order, "amount"));
            return Map.of("id", id, "status", "CANCELLED", "message", "订单已到期，全额退款");
          }
          sweepPending(Db.value(order, "hotel_id"));
          order = db.one("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
          if ("CANCELLED".equals(order.get("status"))) {
            return Map.of("id", id, "status", "CANCELLED", "message", "无房可接，全额退款");
          }
          if (!priorityAllows(order)) {
            throw BusinessException.conflict("有更早付款且可履行的订单优先使用此房量，请先处理该订单");
          }
          allocate(order);
          db.jdbc.update("UPDATE booking SET status='CONFIRMED',version=version+1 WHERE id=?", id);
          audit.record(actor, Db.value(order, "hotel_id"), "CONFIRM", "booking", id, "逐夜房量核验并确认");
          sweepPending(Db.value(order, "hotel_id"));
          return Map.of("id", id, "status", "CONFIRMED");
        });
  }

  private boolean priorityAllows(Map<String, Object> target) {
    long type = Db.value(target, "room_type_id");
    List<Map<String, Object>> candidates =
        db.rows(
            "SELECT * FROM booking WHERE room_type_id=? AND status='PENDING' ORDER BY paid_at,id",
            type);
    Map<LocalDate, Integer> virtual = new HashMap<>();
    for (Map<String, Object> candidate : candidates) {
      List<Map<String, Object>> days =
          pricing.availability(
              type, Db.date(candidate, "check_in"), Db.date(candidate, "check_out"));
      int rooms = (int) Db.value(candidate, "rooms");
      boolean fits =
          days.stream()
              .allMatch(
                  day ->
                      Db.value(day, "remaining")
                              - virtual.getOrDefault(Db.date(day, "stay_date"), 0)
                          >= rooms);
      if (Db.value(candidate, "id") == Db.value(target, "id")) {
        return fits;
      }
      if (fits) {
        for (Map<String, Object> day : days) {
          virtual.merge(Db.date(day, "stay_date"), rooms, Integer::sum);
        }
      }
    }
    return false;
  }

  private boolean hasRoom(Map<String, Object> order) {
    LocalDate start = Db.date(order, "check_in");
    LocalDate end = Db.date(order, "check_out");
    List<Map<String, Object>> days =
        pricing.availability(Db.value(order, "room_type_id"), start, end);
    return days.size() == java.time.temporal.ChronoUnit.DAYS.between(start, end)
        && days.stream()
            .allMatch(
                day ->
                    !Boolean.TRUE.equals(day.get("stop_sale"))
                        && !Boolean.TRUE.equals(day.get("emergency_stop"))
                        && Db.value(day, "remaining") >= Db.value(order, "rooms"));
  }

  public void allocate(Map<String, Object> order) {
    if (!hasRoom(order)) {
      throw BusinessException.conflict("所选住宿夜房量不足");
    }
    List<Map<String, Object>> nights =
        db.rows(
            "SELECT stay_date FROM booking_night WHERE booking_id=? ORDER BY stay_date",
            order.get("id"));
    for (int index = 0; index < nights.size(); index++) {
      db.jdbc.update(
          "UPDATE booking_night SET allocated=TRUE WHERE booking_id=? AND stay_date=?",
          order.get("id"),
          LocalDate.parse(nights.get(index).get("stay_date").toString()));
      if (index == 0) {
        faults.checkpoint("after-first-allocation");
      }
    }
  }

  public void sweepPending(long hotel) {
    for (Map<String, Object> order :
        db.rows(
            "SELECT * FROM booking WHERE hotel_id=? AND status='PENDING' ORDER BY id FOR UPDATE",
            hotel)) {
      if (!clock.now().isBefore(Db.instant(order, "deadline"))) {
        cancelLocked(null, order, "TIMEOUT", Db.value(order, "amount"));
      } else if (!hasRoom(order)) {
        cancelLocked(null, order, "NO_AVAILABILITY", Db.value(order, "amount"));
      }
    }
  }

  public long refundable(Map<String, Object> order, Instant now) {
    String state = Db.string(order, "status");
    if (state.equals("PENDING")) {
      return Db.value(order, "amount");
    }
    if (state.equals("CONFIRMED") && now.isBefore(clock.at(Db.date(order, "check_in"), 18))) {
      return Db.value(order, "amount");
    }
    return 0;
  }

  public Map<String, Object> cancel(Actor actor, long id, String key, Map<String, Object> body) {
    actor.require("CUSTOMER");
    Map<String, Object> before = accessible(actor, id);
    return keys.execute(
        actor,
        "cancel-" + id,
        key,
        body,
        () -> {
          db.lockHotel(Db.value(before, "hotel_id"));
          Map<String, Object> order = accessible(actor, id);
          version(order, body);
          cancellable(order);
          long refund = refundable(order, clock.now());
          if (Input.number(body, "expectedRefund", 0, Long.MAX_VALUE) != refund) {
            throw BusinessException.conflict("退款金额已变化，请重新确认");
          }
          cancelLocked(actor, order, "CUSTOMER_CANCEL", refund);
          return Map.of("id", id, "status", "CANCELLED", "refund", refund);
        });
  }

  public void cancelLocked(Actor actor, Map<String, Object> order, String reason, long refund) {
    cancellable(order);
    long id = Db.value(order, "id");
    if (refund > 0) {
      Map<String, Object> payment =
          db.one("SELECT * FROM money_entry WHERE booking_id=? AND kind='PAYMENT'", id);
      long paid = Db.value(payment, "amount");
      Long refunded =
          db.jdbc.queryForObject(
              "SELECT COALESCE(SUM(amount),0) FROM money_entry WHERE booking_id=? AND"
                  + " kind='REFUND'",
              Long.class,
              id);
      if (refund + refunded > paid) {
        throw BusinessException.conflict("退款不能超过原实付金额");
      }
      db.jdbc.update(
          "INSERT INTO money_entry(booking_id,kind,amount,original_id,reason,created_at)"
              + " VALUES(?,'REFUND',?,?,?,?)",
          id,
          refund,
          payment.get("id"),
          reason,
          Db.time(clock.now()));
    }
    faults.checkpoint("after-refund");
    db.jdbc.update("UPDATE booking_night SET allocated=FALSE WHERE booking_id=? AND allocated", id);
    db.jdbc.update(
        "UPDATE booking SET status='CANCELLED',reason=?,version=version+1 WHERE id=?", reason, id);
    audit.record(
        actor,
        Db.value(order, "hotel_id"),
        "CANCEL",
        "booking",
        id,
        reason + "；退款 " + refund + " 分");
  }

  public Map<String, Object> beginModification(
      Actor actor, long id, String key, Map<String, Object> body) {
    actor.require("CUSTOMER");
    Map<String, Object> before = accessible(actor, id);
    return keys.execute(
        actor,
        "modify-" + id,
        key,
        body,
        () -> {
          db.lockHotel(Db.value(before, "hotel_id"));
          Map<String, Object> order = accessible(actor, id);
          version(order, body);
          cancellable(order);
          Input.require(Input.flag(body, "riskAccepted"), "请确认原房不保留、新预订可能失败的风险");
          String quoteId = Input.text(body, "quoteId", 40);
          Pricing.Calculation quote = pricing.recheckQuote(actor, quoteId);
          Input.require(quote.hotelId() == Db.value(order, "hotel_id"), "修改需选择同一酒店的房型");
          long refund = refundable(order, clock.now());
          if (Input.number(body, "expectedRefund", 0, Long.MAX_VALUE) != refund) {
            throw BusinessException.conflict("退款已变化，请重新确认");
          }
          cancelLocked(actor, order, "MODIFICATION", refund);
          long modification =
              db.insert(
                  "INSERT INTO modification(old_booking_id,customer_id,payload,state,created_at)"
                      + " VALUES(?,?,?::jsonb,'CANCELLED_OLD',?)",
                  id,
                  actor.id(),
                  json.write(
                      Map.of(
                          "quoteId",
                          quoteId,
                          "contact",
                          order.get("contact"),
                          "phone",
                          order.get("phone"))),
                  Db.time(clock.now()));
          return Map.of(
              "id", modification, "oldBookingId", id, "refund", refund, "state", "CANCELLED_OLD");
        });
  }

  public Map<String, Object> finishModification(
      Actor actor, long id, String key, Map<String, Object> body) {
    actor.require("CUSTOMER");
    Map<String, Object> saved =
        db.one("SELECT * FROM modification WHERE id=? AND customer_id=?", id, actor.id());
    Map<String, Object> original = accessible(actor, Db.value(saved, "old_booking_id"));
    return keys.execute(
        actor,
        "complete-modification-" + id,
        key,
        body,
        () -> {
          db.lockHotel(Db.value(original, "hotel_id"));
          Map<String, Object> record =
              db.one("SELECT * FROM modification WHERE id=? FOR UPDATE", id);
          if (record.get("new_booking_id") != null) {
            return Map.of("id", record.get("new_booking_id"), "state", "CREATED_NEW");
          }
          Map<String, Object> parameters = json.object(record.get("payload"));
          if (body.containsKey("contact")) {
            parameters.put("contact", Input.text(body, "contact", 60));
          }
          if (body.containsKey("phone")) {
            parameters.put("phone", Input.text(body, "phone", 30));
          }
          String quoteId =
              body.containsKey("quoteId")
                  ? Input.text(body, "quoteId", 40)
                  : parameters.get("quoteId").toString();
          Input.require(
              Db.value(pricing.loadQuote(actor, quoteId), "hotel_id")
                  == Db.value(original, "hotel_id"),
              "新预订需属于原酒店");
          parameters.put("accepted", Input.flag(body, "accepted"));
          parameters.put("simulateFailure", Input.flag(body, "simulateFailure"));
          Map<String, Object> result =
              createLocked(actor, quoteId, parameters, Db.value(original, "id"));
          if (Boolean.TRUE.equals(result.get("paymentFailed"))) {
            db.jdbc.update(
                "UPDATE modification SET state='FAILED',detail='新付款失败；原单仍已取消' WHERE id=?", id);
          } else {
            db.jdbc.update(
                "UPDATE modification SET state='CREATED_NEW',new_booking_id=?,detail=NULL WHERE"
                    + " id=?",
                result.get("id"),
                id);
          }
          return result;
        });
  }

  public void version(Map<String, Object> row, Map<String, ?> body) {
    if (Input.number(body, "version", 1, Long.MAX_VALUE) != Db.value(row, "version")) {
      throw BusinessException.conflict("记录已更新，请刷新后再操作");
    }
  }

  public void requireState(Map<String, Object> order, String expected) {
    if (!expected.equals(order.get("status"))) {
      throw BusinessException.conflict("订单当前状态不允许此操作");
    }
  }

  public void cancellable(Map<String, Object> order) {
    if (!List.of("PENDING", "CONFIRMED").contains(order.get("status"))) {
      throw BusinessException.conflict("仅未入住的待接单或已确认订单可以取消或修改");
    }
  }

  public void processDue() {
    for (Map<String, Object> hotel : db.rows("SELECT id FROM hotel ORDER BY id")) {
      db.transaction.executeWithoutResult(
          status -> {
            long id = Db.value(hotel, "id");
            db.lockHotel(id);
            for (Map<String, Object> order :
                db.rows(
                    "SELECT * FROM booking WHERE hotel_id=? AND status='PENDING' AND deadline<=?"
                        + " ORDER BY id FOR UPDATE",
                    id,
                    Db.time(clock.now()))) {
              cancelLocked(null, order, "TIMEOUT", Db.value(order, "amount"));
            }
            for (Map<String, Object> order :
                db.rows(
                    "SELECT * FROM booking WHERE hotel_id=? AND status='CONFIRMED' AND check_out<=?"
                        + " ORDER BY id FOR UPDATE",
                    id,
                    clock.today())) {
              if (!clock.now().isBefore(clock.at(Db.date(order, "check_out"), 12))) {
                db.jdbc.update(
                    "UPDATE booking SET status='NO_SHOW',reason='NO_SHOW',version=version+1 WHERE"
                        + " id=?",
                    order.get("id"));
                db.jdbc.update(
                    "UPDATE booking_night SET allocated=FALSE WHERE booking_id=?", order.get("id"));
                audit.record(
                    null, id, "NO_SHOW", "booking", Db.value(order, "id"), "到原退房日12点仍未入住，不退款");
              }
            }
          });
    }
  }
}
