package cn.edu.hotel.catalog;

import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.Idempotency;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.core.Json;
import cn.edu.hotel.identity.Actor;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class Pricing {
  public record Selection(long roomType, LocalDate start, LocalDate end, int rooms, int guests) {
    public Map<String, Object> payload() {
      return Map.of(
          "roomTypeId",
          roomType,
          "start",
          start.toString(),
          "end",
          end.toString(),
          "rooms",
          rooms,
          "guests",
          guests);
    }
  }

  public record Night(LocalDate day, long unitPrice, int available, long version) {}

  public record Calculation(
      long hotelId, Selection selection, List<Night> nights, long total, String hash) {}

  private final Db db;
  private final BusinessClock clock;
  private final Json json;
  private final Idempotency keys;

  public Pricing(Db db, BusinessClock clock, Json json, Idempotency keys) {
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.keys = keys;
  }

  public Selection selection(Map<String, ?> body, boolean futureOnly) {
    LocalDate start = Input.date(body, "start");
    LocalDate end = Input.date(body, "end");
    Input.require(end.isAfter(start) && ChronoUnit.DAYS.between(start, end) <= 30, "住宿需为1—30晚");
    if (futureOnly) {
      Input.require(
          !start.isBefore(clock.today()) && !end.isAfter(clock.today().plusDays(365)),
          "日期需在今天起一年内");
    }
    long type = Input.id(body, "roomTypeId");
    int rooms = (int) Input.number(body, "rooms", 1, 30);
    int guests = (int) Input.number(body, "guests", 1, 30);
    Map<String, Object> room = db.one("SELECT * FROM room_type WHERE id=? AND active", type);
    Input.require(
        rooms <= guests && (long) rooms * Db.value(room, "capacity") >= guests,
        "房间数量须满足入住人数，且不超过人数");
    return new Selection(type, start, end, rooms, guests);
  }

  public Calculation calculate(
      Selection selection, boolean offline, boolean student, boolean enforceAvailable) {
    Map<String, Object> type =
        db.one("SELECT * FROM room_type WHERE id=? AND active", selection.roomType());
    List<Map<String, Object>> days =
        availability(selection.roomType(), selection.start(), selection.end());
    if (days.size() != ChronoUnit.DAYS.between(selection.start(), selection.end())) {
      throw BusinessException.conflict("所选日期尚未开放预订");
    }
    List<Night> nights = new ArrayList<>();
    long total = 0;
    StringBuilder fingerprint = new StringBuilder(json.canonical(selection.payload()));
    for (Map<String, Object> day : days) {
      int remaining = (int) Db.value(day, "remaining");
      boolean stopped =
          Boolean.TRUE.equals(day.get("stop_sale"))
              || Boolean.TRUE.equals(day.get("emergency_stop"));
      if (enforceAvailable && (stopped || remaining < selection.rooms())) {
        throw BusinessException.conflict("至少一晚余房不足或已停售，请重新查房");
      }
      long unit = Db.value(day, offline ? "public_price" : "online_price");
      if (offline && student) {
        unit = (unit * 8 + 5) / 10;
      }
      total = Math.addExact(total, Math.multiplyExact(unit, selection.rooms()));
      LocalDate date = Db.date(day, "stay_date");
      nights.add(new Night(date, unit, stopped ? 0 : remaining, Db.value(day, "version")));
      fingerprint.append('|').append(date).append(':').append(unit);
    }
    return new Calculation(
        Db.value(type, "hotel_id"), selection, nights, total, keys.hash(fingerprint.toString()));
  }

  public List<Map<String, Object>> availability(long type, LocalDate start, LocalDate end) {
    return db.rows(
        """
        SELECT i.*, i.capacity-COALESCE(a.used,0) AS remaining FROM inventory_day i
        LEFT JOIN (SELECT n.stay_date,SUM(n.rooms) AS used FROM booking_night n
          JOIN booking b ON b.id=n.booking_id WHERE b.room_type_id=? AND n.allocated
          AND n.stay_date>=? AND n.stay_date<? GROUP BY n.stay_date) a ON a.stay_date=i.stay_date
        WHERE i.room_type_id=? AND i.stay_date>=? AND i.stay_date<? ORDER BY i.stay_date
        """,
        type,
        start,
        end,
        type,
        start,
        end);
  }

  public Map<String, Object> quote(Actor actor, Map<String, Object> body) {
    boolean offline = Input.flag(body, "offline");
    boolean student = Input.flag(body, "student");
    if (offline) {
      actor.require("STAFF");
    } else {
      actor.require("CUSTOMER");
    }
    Input.require(!student || offline, "学生折扣只适用于前台核验后的线下订单");
    Selection choice = selection(body, true);
    return db.transaction.execute(
        status -> {
          long hotel =
              Db.value(
                  db.one("SELECT hotel_id FROM room_type WHERE id=?", choice.roomType()),
                  "hotel_id");
          if (offline) {
            actor.staff(hotel);
          }
          db.lockHotel(hotel);
          Calculation calculated = calculate(choice, offline, student, true);
          Map<String, Object> payload = new LinkedHashMap<>(choice.payload());
          payload.put("offline", offline);
          payload.put("student", student);
          UUID id = UUID.randomUUID();
          db.jdbc.update(
              "INSERT INTO"
                  + " quote(id,actor_id,hotel_id,purpose,payload,price_hash,amount,created_at,expires_at)"
                  + " VALUES(?,?,?,'BOOKING',?::jsonb,?,?,?,?)",
              id,
              actor.id(),
              hotel,
              json.write(payload),
              calculated.hash(),
              calculated.total(),
              Db.time(clock.now()),
              Db.time(clock.now().plusSeconds(600)));
          return Map.of(
              "id",
              id,
              "amount",
              calculated.total(),
              "nights",
              calculated.nights(),
              "selection",
              payload,
              "expiresAt",
              clock.now().plusSeconds(600).toString());
        });
  }

  public UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw BusinessException.invalid("报价标识无效");
    }
  }

  public Map<String, Object> loadQuote(Actor actor, String id) {
    return db.one("SELECT * FROM quote WHERE id=? AND actor_id=?", uuid(id), actor.id());
  }

  public Calculation recheckQuote(Actor actor, String id) {
    Map<String, Object> quote = loadQuote(actor, id);
    if (quote.get("used_booking_id") != null) {
      throw BusinessException.conflict("此报价已经生成订单，请查看订单结果");
    }
    if (!clock.now().isBefore(Db.instant(quote, "expires_at"))) {
      throw BusinessException.conflict("报价已过期，请重新核对费用");
    }
    Map<String, Object> payload = json.object(quote.get("payload"));
    boolean offline = Input.flag(payload, "offline");
    if (offline) {
      actor.staff(Db.value(quote, "hotel_id"));
    } else {
      actor.require("CUSTOMER");
    }
    Calculation current =
        calculate(selection(payload, true), offline, Input.flag(payload, "student"), true);
    if (!current.hash().equals(quote.get("price_hash"))) {
      throw BusinessException.conflict("价格已变化，请重新确认报价");
    }
    return current;
  }
}
