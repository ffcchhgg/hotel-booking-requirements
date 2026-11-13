package cn.edu.hotel.management;

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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ManagementService {
  private final Db db;
  private final Json json;
  private final BusinessClock clock;
  private final Idempotency keys;
  private final Audit audit;
  private final Pricing pricing;
  private final BookingService bookings;

  public ManagementService(
      Db db,
      Json json,
      BusinessClock clock,
      Idempotency keys,
      Audit audit,
      Pricing pricing,
      BookingService bookings) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.keys = keys;
    this.audit = audit;
    this.pricing = pricing;
    this.bookings = bookings;
  }

  public void scope(Actor actor, long hotel) {
    if (actor.is("STAFF")) {
      actor.staff(hotel);
    } else {
      actor.require("ADMIN");
    }
  }

  public Map<String, Object> catalog(Actor actor, long hotel, LocalDate start, LocalDate end) {
    scope(actor, hotel);
    dateRange(start, end, 90, false);
    return Map.of(
        "hotel",
        db.one("SELECT * FROM hotel WHERE id=?", hotel),
        "types",
        db.rows("SELECT * FROM room_type WHERE hotel_id=? ORDER BY id", hotel),
        "inventory",
        db.rows(
            """
SELECT i.*,r.name AS room_type_name,
  COALESCE((SELECT SUM(n.rooms) FROM booking_night n JOIN booking b ON b.id=n.booking_id
    WHERE b.room_type_id=i.room_type_id AND n.stay_date=i.stay_date AND n.allocated),0) AS used
FROM inventory_day i JOIN room_type r ON r.id=i.room_type_id
WHERE r.hotel_id=? AND i.stay_date>=? AND i.stay_date<? ORDER BY i.stay_date,r.id
""",
            hotel,
            start,
            end),
        "feePolicies",
        db.rows(
            "SELECT hourly_cents,created_at FROM fee_policy WHERE hotel_id=? ORDER BY id DESC LIMIT"
                + " 5",
            hotel));
  }

  public void dateRange(LocalDate start, LocalDate end, int maximum, boolean future) {
    Input.require(
        end.isAfter(start) && ChronoUnit.DAYS.between(start, end) <= maximum,
        "日期范围须为1—" + maximum + "天（结束日不含）");
    if (future) {
      Input.require(
          !start.isBefore(clock.today()) && !end.isAfter(clock.today().plusDays(365)),
          "只能调整今天起一年内的数据");
    }
  }

  private String approvedImage(Actor actor, Map<String, Object> body) {
    String value = Input.text(body, "image", 200);
    if (List.of("/assets/garden.jpg", "/assets/room.jpg", "/assets/suite.jpg").contains(value)) {
      return value;
    }
    Input.require(value.startsWith("/api/images/"), "请选择已上传的酒店图片");
    db.one(
        "SELECT id FROM uploaded_image WHERE id=? AND hotel_id=?",
        pricing.uuid(value.substring(12)),
        actor.hotelId());
    return value;
  }

  private void validateInventory(
      long type, LocalDate start, LocalDate end, Map<String, Object> payload) {
    long capacity = Input.number(payload, "capacity", 0, 500);
    Input.number(payload, "publicPrice", 0, 100000000);
    Input.number(payload, "onlinePrice", 0, 100000000);
    boolean stopped = Input.flag(payload, "stopSale");
    long physical =
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM physical_room WHERE room_type_id=? AND state<>'DISABLED'",
            Long.class,
            type);
    Input.require(capacity <= physical, "可售房量不能超过已登记的可用实体房间数量");
    List<Map<String, Object>> days = pricing.availability(type, start, end);
    Input.require(days.size() == ChronoUnit.DAYS.between(start, end), "该日期尚未建立房量日历");
    for (Map<String, Object> day : days) {
      long used = Db.value(day, "capacity") - Db.value(day, "remaining");
      if (capacity < used || (stopped && used > 0)) {
        throw BusinessException.conflict("不能减少到已承诺房量以下或直接停售已有预订的日期；请使用应急处理");
      }
    }
  }

  public Map<String, Object> submit(Actor actor, String key, Map<String, Object> body) {
    actor.require("STAFF");
    return keys.execute(
        actor,
        "submit-change",
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          String kind = Input.text(body, "kind", 20);
          long target = Input.id(body, "targetId");
          Map<String, Object> payload = new LinkedHashMap<>();
          Map<String, Object> baseline = new LinkedHashMap<>();
          if (kind.equals("HOTEL") || kind.equals("ROOM_TYPE")) {
            Map<String, Object> row =
                kind.equals("HOTEL")
                    ? db.one("SELECT * FROM hotel WHERE id=? AND id=?", target, actor.hotelId())
                    : db.one(
                        "SELECT * FROM room_type WHERE id=? AND hotel_id=?",
                        target,
                        actor.hotelId());
            Input.require(
                Input.number(body, "version", 1, Long.MAX_VALUE) == Db.value(row, "version"),
                "资料已更新，请重新读取后提交");
            payload.put("name", Input.text(body, "name", kind.equals("HOTEL") ? 100 : 60));
            payload.put("description", Input.text(body, "description", 2000));
            payload.put("image", approvedImage(actor, body));
            baseline.put("version", row.get("version"));
          } else if (kind.equals("INVENTORY")) {
            db.one("SELECT id FROM room_type WHERE id=? AND hotel_id=?", target, actor.hotelId());
            LocalDate start = Input.date(body, "start");
            LocalDate end = Input.date(body, "end");
            dateRange(start, end, 30, true);
            payload.put("start", start.toString());
            payload.put("end", end.toString());
            payload.put("capacity", Input.number(body, "capacity", 0, 500));
            payload.put("publicPrice", Input.number(body, "publicPrice", 0, 100000000));
            payload.put("onlinePrice", Input.number(body, "onlinePrice", 0, 100000000));
            payload.put("stopSale", Input.flag(body, "stopSale"));
            validateInventory(target, start, end, payload);
            for (Map<String, Object> day : pricing.availability(target, start, end)) {
              baseline.put(Db.string(day, "stay_date"), day.get("version"));
            }
          } else {
            throw BusinessException.invalid("资料变更类型无效");
          }
          long id =
              db.insert(
                  "INSERT INTO"
                      + " change_request(hotel_id,author_id,kind,target_id,payload,baseline,created_at)"
                      + " VALUES(?,?,?,?,?::jsonb,?::jsonb,?)",
                  actor.hotelId(),
                  actor.id(),
                  kind,
                  target,
                  json.write(payload),
                  json.write(baseline),
                  Db.time(clock.now()));
          audit.record(
              actor, actor.hotelId(), "SUBMIT_CHANGE", "change", id, "已提交" + kind + "资料，审核前不对外生效");
          return Map.of("id", id, "status", "PENDING");
        });
  }

  public Map<String, Object> changes(Actor actor, long hotel) {
    scope(actor, hotel);
    List<Map<String, Object>> rows =
        db.rows(
            "SELECT c.*,u.display_name AS author_name FROM change_request c JOIN app_user u ON"
                + " u.id=c.author_id WHERE c.hotel_id=? ORDER BY c.id DESC LIMIT 100",
            hotel);
    for (Map<String, Object> row : rows) {
      row.put("payload", json.object(row.get("payload")));
      row.put("baseline", json.object(row.get("baseline")));
    }
    return Map.of("items", rows);
  }

  public Map<String, Object> review(Actor actor, long id, String key, Map<String, Object> body) {
    actor.require("ADMIN");
    Map<String, Object> initial = db.one("SELECT hotel_id FROM change_request WHERE id=?", id);
    long hotel = Db.value(initial, "hotel_id");
    return keys.execute(
        actor,
        "review-" + id,
        key,
        body,
        () -> {
          db.lockHotel(hotel);
          Map<String, Object> change =
              db.one("SELECT * FROM change_request WHERE id=? FOR UPDATE", id);
          if (!"PENDING".equals(change.get("status"))) {
            throw BusinessException.conflict("该申请已经完成审核");
          }
          boolean approve = Input.flag(body, "approve");
          String note = Input.text(body, "note", 300);
          Map<String, Object> payload = json.object(change.get("payload"));
          Map<String, Object> baseline = json.object(change.get("baseline"));
          long target = Db.value(change, "target_id");
          String kind = Db.string(change, "kind");
          if (approve) {
            if (kind.equals("INVENTORY")) {
              LocalDate start = Input.date(payload, "start");
              LocalDate end = Input.date(payload, "end");
              dateRange(start, end, 30, true);
              validateInventory(target, start, end, payload);
              for (Map<String, Object> day : pricing.availability(target, start, end)) {
                Object previous = baseline.get(Db.string(day, "stay_date"));
                if (!(previous instanceof Number number)
                    || number.longValue() != Db.value(day, "version")) {
                  throw BusinessException.conflict("房量或房价已被其他操作更新，请驳回后重新提交");
                }
              }
              db.jdbc.update(
                  "UPDATE inventory_day SET"
                      + " capacity=?,public_price=?,online_price=?,stop_sale=?,version=version+1"
                      + " WHERE room_type_id=? AND stay_date>=? AND stay_date<?",
                  payload.get("capacity"),
                  payload.get("publicPrice"),
                  payload.get("onlinePrice"),
                  payload.get("stopSale"),
                  target,
                  start,
                  end);
              bookings.sweepPending(hotel);
            } else {
              String table = kind.equals("HOTEL") ? "hotel" : "room_type";
              long version =
                  Db.value(
                      db.one("SELECT version FROM " + table + " WHERE id=?", target), "version");
              if (version != Db.value(baseline, "version")) {
                throw BusinessException.conflict("当前资料与提交时版本不一致，请驳回后重新提交");
              }
              db.jdbc.update(
                  "UPDATE "
                      + table
                      + " SET name=?,description=?,image=?,version=version+1 WHERE id=?",
                  payload.get("name"),
                  payload.get("description"),
                  payload.get("image"),
                  target);
            }
          }
          String status = approve ? "APPROVED" : "REJECTED";
          db.jdbc.update(
              "UPDATE change_request SET status=?,reviewer_id=?,note=?,reviewed_at=? WHERE id=?",
              status,
              actor.id(),
              note,
              Db.time(clock.now()),
              id);
          audit.record(actor, hotel, "REVIEW_CHANGE", "change", id, status);
          return Map.of("id", id, "status", status);
        });
  }

  public Map<String, Object> fee(Actor actor, long hotel, String key, Map<String, Object> body) {
    actor.require("ADMIN");
    return keys.execute(
        actor,
        "fee-" + hotel,
        key,
        body,
        () -> {
          db.lockHotel(hotel);
          long rate = Input.number(body, "hourlyRate", 0, 1000000);
          long id =
              db.insert(
                  "INSERT INTO fee_policy(hotel_id,hourly_cents,created_by,created_at)"
                      + " VALUES(?,?,?,?)",
                  hotel,
                  rate,
                  actor.id(),
                  Db.time(clock.now()));
          audit.record(actor, hotel, "FEE_POLICY", "fee", id, "新入住订单每超时小时 " + rate + " 分；已有入住费率不变");
          return Map.of("id", id, "hourlyRate", rate);
        });
  }

  public Map<String, Object> logs(Actor actor, long hotel, int page) {
    actor.require("ADMIN");
    Input.require(page >= 1 && page <= 100000, "页码无效");
    return Map.of(
        "total",
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation_log WHERE hotel_id=?", Long.class, hotel),
        "items",
        db.rows(
            "SELECT l.*,COALESCE(u.display_name,'系统任务') AS actor_name FROM operation_log l LEFT"
                + " JOIN app_user u ON u.id=l.actor_id WHERE l.hotel_id=? ORDER BY l.id DESC LIMIT"
                + " 30 OFFSET ?",
            hotel,
            (page - 1) * 30));
  }
}
