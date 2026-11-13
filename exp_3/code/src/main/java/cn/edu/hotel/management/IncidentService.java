package cn.edu.hotel.management;

import cn.edu.hotel.booking.BookingService;
import cn.edu.hotel.core.Audit;
import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.Idempotency;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.identity.Actor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IncidentService {
  private final Db db;
  private final ManagementService management;
  private final BookingService bookings;
  private final BusinessClock clock;
  private final Idempotency keys;
  private final Audit audit;

  public IncidentService(
      Db db,
      ManagementService management,
      BookingService bookings,
      BusinessClock clock,
      Idempotency keys,
      Audit audit) {
    this.db = db;
    this.management = management;
    this.bookings = bookings;
    this.clock = clock;
    this.keys = keys;
    this.audit = audit;
  }

  private List<Map<String, Object>> affected(long type, LocalDate start, LocalDate end) {
    return db.rows(
        "SELECT id,contact,check_in,check_out,rooms,status,amount FROM booking WHERE room_type_id=?"
            + " AND check_in<? AND check_out>? AND status IN ('PENDING','CONFIRMED','CHECKED_IN')"
            + " ORDER BY id",
        type,
        end,
        start);
  }

  public Map<String, Object> preview(Actor actor, Map<String, ?> body) {
    actor.require("STAFF");
    long type = Input.id(body, "roomTypeId");
    db.one("SELECT id FROM room_type WHERE id=? AND hotel_id=?", type, actor.hotelId());
    LocalDate start = Input.date(body, "start");
    LocalDate end = Input.date(body, "end");
    management.dateRange(start, end, 30, true);
    return Map.of("items", affected(type, start, end));
  }

  public Map<String, Object> open(Actor actor, String key, Map<String, Object> body) {
    actor.require("STAFF");
    return keys.execute(
        actor,
        "open-incident",
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          preview(actor, body);
          Input.require(Input.flag(body, "confirmed"), "请确认停售并对未入住订单全额退款");
          long type = Input.id(body, "roomTypeId");
          LocalDate start = Input.date(body, "start");
          LocalDate end = Input.date(body, "end");
          String reason = Input.text(body, "reason", 300);
          long id =
              db.insert(
                  "INSERT INTO"
                      + " incident(hotel_id,room_type_id,start_date,end_date,reason,actor_id,created_at)"
                      + " VALUES(?,?,?,?,?,?,?)",
                  actor.hotelId(),
                  type,
                  start,
                  end,
                  reason,
                  actor.id(),
                  Db.time(clock.now()));
          db.jdbc.update(
              "UPDATE inventory_day SET emergency_stop=TRUE,version=version+1 WHERE room_type_id=?"
                  + " AND stay_date>=? AND stay_date<?",
              type,
              start,
              end);
          int refunded = 0;
          int manual = 0;
          for (Map<String, Object> item : affected(type, start, end)) {
            boolean inHouse = "CHECKED_IN".equals(item.get("status"));
            if (inHouse) {
              manual++;
            } else {
              bookings.cancelLocked(
                  actor,
                  db.one("SELECT * FROM booking WHERE id=?", item.get("id")),
                  "INCIDENT_" + id,
                  Db.value(item, "amount"));
              refunded++;
            }
            db.jdbc.update(
                "INSERT INTO incident_case(incident_id,booking_id,state) VALUES(?,?,?)",
                id,
                item.get("id"),
                inHouse ? "MANUAL" : "REFUNDED");
          }
          audit.record(
              actor,
              actor.hotelId(),
              "OPEN_INCIDENT",
              "incident",
              id,
              "已停售；未入住全退 " + refunded + " 单，在住待人工处理 " + manual + " 单");
          return Map.of("id", id, "refunded", refunded, "manual", manual, "status", "OPEN");
        });
  }

  public Map<String, Object> list(Actor actor, long hotel) {
    management.scope(actor, hotel);
    List<Map<String, Object>> rows =
        db.rows(
            "SELECT i.*,r.name AS room_type_name FROM incident i JOIN room_type r ON"
                + " r.id=i.room_type_id WHERE i.hotel_id=? ORDER BY i.id DESC LIMIT 100",
            hotel);
    for (Map<String, Object> row : rows) {
      row.put(
          "cases",
          db.rows(
              "SELECT c.*,b.contact,b.status AS booking_status FROM incident_case c JOIN booking b"
                  + " ON b.id=c.booking_id WHERE c.incident_id=? ORDER BY c.booking_id",
              row.get("id")));
    }
    return Map.of("items", rows);
  }

  public Map<String, Object> resolve(
      Actor actor, long id, long booking, String key, Map<String, Object> body) {
    actor.require("STAFF");
    return keys.execute(
        actor,
        "incident-case-" + id + "-" + booking,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          db.one(
              "SELECT id FROM incident WHERE id=? AND hotel_id=? AND status='OPEN'",
              id,
              actor.hotelId());
          String note = Input.text(body, "note", 300);
          int count =
              db.jdbc.update(
                  "UPDATE incident_case SET state='RESOLVED',note=? WHERE incident_id=? AND"
                      + " booking_id=? AND state='MANUAL'",
                  note,
                  id,
                  booking);
          if (count != 1) {
            throw BusinessException.conflict("该在住事项已办结或不存在");
          }
          audit.record(
              actor,
              actor.hotelId(),
              "RESOLVE_INCIDENT_CASE",
              "booking",
              booking,
              "已记录应急在住事项结论，未自动改变原住宿费用");
          return Map.of("state", "RESOLVED");
        });
  }

  public Map<String, Object> close(Actor actor, long id, String key, Map<String, Object> body) {
    actor.require("STAFF");
    return keys.execute(
        actor,
        "close-incident-" + id,
        key,
        body,
        () -> {
          db.lockHotel(actor.hotelId());
          Map<String, Object> incident =
              db.one("SELECT * FROM incident WHERE id=? AND hotel_id=?", id, actor.hotelId());
          Input.require(Input.flag(body, "restored"), "请先确认酒店已恢复接待能力");
          if (!"OPEN".equals(incident.get("status"))) {
            throw BusinessException.conflict("应急事件已经关闭");
          }
          long pending =
              db.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM incident_case WHERE incident_id=? AND state='MANUAL'",
                  Long.class,
                  id);
          if (pending > 0) {
            throw BusinessException.conflict("还有在住事项没有记录处理结论");
          }
          db.jdbc.update(
              "UPDATE incident SET status='CLOSED',closed_at=? WHERE id=?",
              Db.time(clock.now()),
              id);
          db.jdbc.update(
              """
              UPDATE inventory_day d SET emergency_stop=EXISTS(
                SELECT 1 FROM incident i WHERE i.room_type_id=d.room_type_id AND i.status='OPEN'
                AND i.start_date<=d.stay_date AND i.end_date>d.stay_date),version=version+1
              WHERE d.room_type_id=? AND d.stay_date>=? AND d.stay_date<?
              """,
              incident.get("room_type_id"),
              Db.date(incident, "start_date"),
              Db.date(incident, "end_date"));
          audit.record(
              actor,
              actor.hotelId(),
              "CLOSE_INCIDENT",
              "incident",
              id,
              "已恢复接待；其他未关闭事件和常规停售设置仍独立生效");
          return Map.of("id", id, "status", "CLOSED");
        });
  }
}
