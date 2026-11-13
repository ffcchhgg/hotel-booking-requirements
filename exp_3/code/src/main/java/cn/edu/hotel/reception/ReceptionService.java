package cn.edu.hotel.reception;

import cn.edu.hotel.booking.BookingService;
import cn.edu.hotel.catalog.Pricing;
import cn.edu.hotel.core.Audit;
import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.Idempotency;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.core.Json;
import cn.edu.hotel.identity.Actor;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReceptionService {
  private final Db db;
  private final BookingService bookings;
  private final Pricing pricing;
  private final Idempotency keys;
  private final BusinessClock clock;
  private final Audit audit;
  private final Json json;

  public ReceptionService(
      Db db,
      BookingService bookings,
      Pricing pricing,
      Idempotency keys,
      BusinessClock clock,
      Audit audit,
      Json json) {
    this.db = db;
    this.bookings = bookings;
    this.pricing = pricing;
    this.keys = keys;
    this.clock = clock;
    this.audit = audit;
    this.json = json;
  }

  public Map<String, Object> rooms(Actor actor) {
    actor.require("STAFF");
    return Map.of(
        "items",
        db.rows(
            """
SELECT p.*,r.name AS room_type_name,r.capacity,s.booking_id FROM physical_room p
JOIN room_type r ON r.id=p.room_type_id LEFT JOIN stay s ON s.room_id=p.id AND s.ended_at IS NULL
WHERE p.hotel_id=? ORDER BY p.number
""",
            actor.hotelId()));
  }

  public Map<String, Object> checkIn(Actor actor, long id, String key, Map<String, Object> body) {
    Map<String, Object> before = bookings.accessible(actor, id);
    actor.staff(Db.value(before, "hotel_id"));
    return keys.execute(
        actor,
        "check-in-" + id,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> order = bookings.accessible(actor, id);
          bookings.version(order, body);
          bookings.requireState(order, "CONFIRMED");
          Input.require(
              !clock.today().isBefore(Db.date(order, "check_in"))
                  && clock.now().isBefore(clock.at(Db.date(order, "check_out"), 12)),
              "当前时间不在可办理入住区间");
          Input.require(Input.flag(body, "identityVerified"), "请完成全部住客身份核验");
          if (Input.flag(body, "proxyBooking")) {
            Input.require(Input.flag(body, "phoneVerified"), "代订须电话联系下单人并核验");
          }
          List<Map<String, Object>> assignments = Input.objects(body, "assignments");
          Input.require(assignments.size() == Db.value(order, "rooms"), "必须为订单的全部房间安排入住");
          List<Map<String, Object>> policies =
              db.rows(
                  "SELECT hourly_cents FROM fee_policy WHERE hotel_id=? ORDER BY id DESC LIMIT 1",
                  actor.hotelId());
          if (policies.isEmpty()) {
            throw BusinessException.conflict("请先由管理员设置超时费率");
          }
          long rate = Db.value(policies.getFirst(), "hourly_cents");
          long capacity =
              Db.value(
                  db.one("SELECT capacity FROM room_type WHERE id=?", order.get("room_type_id")),
                  "capacity");
          HashSet<Long> uniqueRooms = new HashSet<>();
          HashSet<String> documents = new HashSet<>();
          int guestCount = 0;
          for (Map<String, Object> assignment : assignments) {
            long roomId = Input.id(assignment, "roomId");
            Input.require(uniqueRooms.add(roomId), "不能重复安排同一房间");
            Map<String, Object> room =
                db.one(
                    "SELECT * FROM physical_room WHERE id=? AND hotel_id=? FOR UPDATE",
                    roomId,
                    actor.hotelId());
            if (!"READY".equals(room.get("state"))
                || Db.value(room, "room_type_id") != Db.value(order, "room_type_id")) {
              throw BusinessException.conflict("房间必须属于所订房型，且已清洁可安排");
            }
            List<Map<String, Object>> guests = Input.objects(assignment, "guests");
            Input.require(guests.size() <= capacity, "单间住客人数超过房型容量");
            long stay =
                db.insert(
                    "INSERT INTO stay(booking_id,room_id,started_at,hourly_cents) VALUES(?,?,?,?)",
                    id,
                    roomId,
                    Db.time(clock.now()),
                    rate);
            for (Map<String, Object> guest : guests) {
              String name = Input.text(guest, "name", 60);
              String phone = Input.text(guest, "phone", 30);
              String document = Input.text(guest, "document", 40);
              Input.require(
                  phone.matches("[+0-9 -]{6,30}") && document.matches("[a-zA-Z0-9-]{6,40}"),
                  "住客联系电话或证件格式无效");
              Input.require(
                  documents.add(document.toUpperCase(java.util.Locale.ROOT)), "同一住客不能重复登记");
              db.jdbc.update(
                  "INSERT INTO guest_registration(stay_id,name,phone,document_number)"
                      + " VALUES(?,?,?,?)",
                  stay,
                  name,
                  phone,
                  document);
              guestCount++;
            }
            db.jdbc.update("UPDATE physical_room SET state='OCCUPIED' WHERE id=?", roomId);
          }
          Input.require(guestCount == Db.value(order, "guest_count"), "请登记与订单人数一致的全部住客");
          db.jdbc.update("UPDATE booking SET status='CHECKED_IN',version=version+1 WHERE id=?", id);
          audit.record(
              actor, actor.hotelId(), "CHECK_IN", "booking", id, "完成 " + guestCount + " 位住客核验与分房");
          return Map.of("id", id, "status", "CHECKED_IN");
        });
  }

  public static long lateFee(long seconds, long rate) {
    return seconds <= 0 ? 0 : Math.multiplyExact((seconds + 3599) / 3600, rate);
  }

  public Map<String, Object> checkoutPreview(Actor actor, long id) {
    Map<String, Object> order = bookings.accessible(actor, id);
    actor.staff(Db.value(order, "hotel_id"));
    bookings.requireState(order, "CHECKED_IN");
    long rate =
        Db.value(
            db.one("SELECT hourly_cents FROM stay WHERE booking_id=? ORDER BY id LIMIT 1", id),
            "hourly_cents");
    Duration overdue = Duration.between(clock.at(Db.date(order, "check_out"), 12), clock.now());
    long seconds =
        overdue.isNegative() ? 0 : overdue.getSeconds() + (overdue.getNano() > 0 ? 1 : 0);
    return Map.of(
        "id",
        id,
        "version",
        order.get("version"),
        "fee",
        lateFee(seconds, rate),
        "hourlyRate",
        rate,
        "early",
        clock.today().isBefore(Db.date(order, "check_out")),
        "rule",
        "每订单每超时小时计费，不足一小时按一小时；使用入住时费率");
  }

  public Map<String, Object> checkout(Actor actor, long id, String key, Map<String, Object> body) {
    Map<String, Object> before = bookings.accessible(actor, id);
    actor.staff(Db.value(before, "hotel_id"));
    return keys.execute(
        actor,
        "check-out-" + id,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> order = bookings.accessible(actor, id);
          bookings.version(order, body);
          bookings.requireState(order, "CHECKED_IN");
          if (clock.today().isBefore(Db.date(order, "check_out"))) {
            throw BusinessException.conflict("提前退房请使用接待事项登记，不能套用普通取消退款");
          }
          Map<String, Object> preview = checkoutPreview(actor, id);
          long fee = (long) preview.get("fee");
          if (Input.number(body, "expectedFee", 0, Long.MAX_VALUE) != fee) {
            throw BusinessException.conflict("超时费用已变化，请重新确认");
          }
          if (fee > 0) {
            Input.require(Input.flag(body, "paymentConfirmed"), "请确认超时费用模拟收款");
            db.jdbc.update(
                "INSERT INTO money_entry(booking_id,kind,amount,reason,created_at)"
                    + " VALUES(?,'LATE_FEE',?,'CHECKOUT',?)",
                id,
                fee,
                Db.time(clock.now()));
          }
          List<Map<String, Object>> stays =
              db.rows(
                  "SELECT * FROM stay WHERE booking_id=? AND ended_at IS NULL ORDER BY room_id FOR"
                      + " UPDATE",
                  id);
          for (Map<String, Object> stay : stays) {
            db.jdbc.update(
                "UPDATE physical_room SET state='DIRTY' WHERE id=?", stay.get("room_id"));
            db.jdbc.update(
                "UPDATE stay SET ended_at=? WHERE id=?", Db.time(clock.now()), stay.get("id"));
          }
          db.jdbc.update(
              "UPDATE booking SET status='CHECKED_OUT',version=version+1 WHERE id=?", id);
          audit.record(
              actor, actor.hotelId(), "CHECK_OUT", "booking", id, "已退房，房间待清洁；超时费 " + fee + " 分");
          return Map.of("id", id, "status", "CHECKED_OUT", "fee", fee);
        });
  }

  public Map<String, Object> clean(Actor actor, long id, String key) {
    actor.require("STAFF");
    return keys.execute(
        actor,
        "clean-" + id,
        key,
        Map.of("id", id),
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> room =
              db.one(
                  "SELECT * FROM physical_room WHERE id=? AND hotel_id=? FOR UPDATE",
                  id,
                  actor.hotelId());
          if (!"DIRTY".equals(room.get("state"))) {
            throw BusinessException.conflict("仅待清洁房间可确认清洁");
          }
          db.jdbc.update("UPDATE physical_room SET state='READY' WHERE id=?", id);
          audit.record(actor, actor.hotelId(), "CLEAN", "room", id, "清洁完成，可安排入住");
          return Map.of("id", id, "state", "READY");
        });
  }

  private Pricing.Calculation extensionCalculation(Map<String, Object> order, LocalDate end) {
    LocalDate start = Db.date(order, "check_out");
    Input.require(
        end.isAfter(start)
            && !end.isAfter(Db.date(order, "check_in").plusDays(30))
            && !end.isAfter(clock.today().plusDays(365)),
        "续住后总住宿时长须不超过30晚");
    if (!clock.now().isBefore(clock.at(start, 12))) {
      throw BusinessException.conflict("已过原退房期限，请登记接待事项处理，不自动转为续住");
    }
    Pricing.Selection selection =
        new Pricing.Selection(
            Db.value(order, "room_type_id"),
            start,
            end,
            (int) Db.value(order, "rooms"),
            (int) Db.value(order, "guest_count"));
    return pricing.calculate(
        selection,
        "OFFLINE".equals(order.get("channel")),
        Boolean.TRUE.equals(order.get("student_discount")),
        true);
  }

  public Map<String, Object> extensionPreview(Actor actor, long id, LocalDate end) {
    Map<String, Object> order = bookings.accessible(actor, id);
    actor.staff(Db.value(order, "hotel_id"));
    bookings.requireState(order, "CHECKED_IN");
    Pricing.Calculation calculation = extensionCalculation(order, end);
    return Map.of(
        "amount",
        calculation.total(),
        "priceHash",
        calculation.hash(),
        "version",
        order.get("version"),
        "end",
        end.toString(),
        "nights",
        calculation.nights());
  }

  public Map<String, Object> extend(Actor actor, long id, String key, Map<String, Object> body) {
    Map<String, Object> before = bookings.accessible(actor, id);
    actor.staff(Db.value(before, "hotel_id"));
    return keys.execute(
        actor,
        "extend-" + id,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> order = bookings.accessible(actor, id);
          bookings.requireState(order, "CHECKED_IN");
          bookings.version(order, body);
          Pricing.Calculation calculation = extensionCalculation(order, Input.date(body, "end"));
          if (!calculation.hash().equals(Input.text(body, "priceHash", 64))) {
            throw BusinessException.conflict("续住报价已变化，请重新确认");
          }
          Input.require(Input.flag(body, "paymentConfirmed"), "请确认续住费用模拟收款");
          for (Pricing.Night night : calculation.nights()) {
            db.jdbc.update(
                "INSERT INTO booking_night(booking_id,stay_date,unit_price,rooms,allocated)"
                    + " VALUES(?,?,?,?,TRUE)",
                id,
                night.day(),
                night.unitPrice(),
                order.get("rooms"));
          }
          db.jdbc.update(
              "UPDATE booking SET check_out=?,amount=amount+?,version=version+1 WHERE id=?",
              calculation.selection().end(),
              calculation.total(),
              id);
          db.jdbc.update(
              "INSERT INTO money_entry(booking_id,kind,amount,reason,created_at)"
                  + " VALUES(?,'EXTENSION',?,?,?)",
              id,
              calculation.total(),
              "TO_" + calculation.selection().end(),
              Db.time(clock.now()));
          audit.record(
              actor,
              actor.hotelId(),
              "EXTEND",
              "booking",
              id,
              "续住至 " + calculation.selection().end() + "，补款 " + calculation.total() + " 分");
          bookings.sweepPending(actor.hotelId());
          return Map.of(
              "id",
              id,
              "end",
              calculation.selection().end().toString(),
              "amount",
              calculation.total());
        });
  }

  public Map<String, Object> guests(Actor actor, long id) {
    Map<String, Object> order = bookings.accessible(actor, id);
    actor.staff(Db.value(order, "hotel_id"));
    // Display only masked documents. Full values are not needed for routine review.
    return Map.of(
        "items",
        db.rows(
            "SELECT"
                + " g.name,g.phone,repeat('*',GREATEST(length(g.document_number)-4,0))||right(g.document_number,4)"
                + " AS document,p.number FROM guest_registration g JOIN stay s ON s.id=g.stay_id"
                + " JOIN physical_room p ON p.id=s.room_id WHERE s.booking_id=? ORDER BY"
                + " p.number,g.id",
            id));
  }

  public Map<String, Object> request(Actor actor, long id, String key, Map<String, Object> body) {
    Map<String, Object> order = bookings.accessible(actor, id);
    actor.staff(Db.value(order, "hotel_id"));
    return keys.execute(
        actor,
        "reception-request-" + id,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> current = bookings.accessible(actor, id);
          bookings.requireState(current, "CHECKED_IN");
          String reason = Input.text(body, "reason", 300);
          String kind = Input.text(body, "kind", 30);
          Input.require(
              List.of("EARLY_DEPARTURE", "OVERDUE_EXTENSION", "OTHER").contains(kind), "接待事项类型无效");
          long request =
              db.insert(
                  "INSERT INTO reception_request(booking_id,kind,reason,actor_id,created_at)"
                      + " VALUES(?,?,?,?,?)",
                  id,
                  kind,
                  reason,
                  actor.id(),
                  Db.time(clock.now()));
          audit.record(
              actor, actor.hotelId(), "RECEPTION_REQUEST", "booking", id, "已登记人工接待事项 " + kind);
          return Map.of("id", request, "status", "OPEN");
        });
  }

  public Map<String, Object> resolveRequest(
      Actor actor, long id, String key, Map<String, Object> body) {
    actor.require("STAFF");
    return keys.execute(
        actor,
        "resolve-reception-" + id,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> request =
              db.one(
                  "SELECT r.* FROM reception_request r JOIN booking b ON b.id=r.booking_id WHERE"
                      + " r.id=? AND b.hotel_id=? FOR UPDATE OF r",
                  id,
                  actor.hotelId());
          if (!"OPEN".equals(request.get("status"))) {
            throw BusinessException.conflict("事项已经办结");
          }
          String resolution = Input.text(body, "resolution", 300);
          db.jdbc.update(
              "UPDATE reception_request SET status='RESOLVED',resolution=?,resolved_at=? WHERE"
                  + " id=?",
              resolution,
              Db.time(clock.now()),
              id);
          audit.record(
              actor, actor.hotelId(), "RESOLVE_RECEPTION", "request", id, "已记录人工处理结论；不自动更改退款或订单");
          return Map.of("id", id, "status", "RESOLVED");
        });
  }
}
