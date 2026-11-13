package cn.edu.hotel.catalog;

import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.identity.Actor;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {
  private final Db db;
  private final Pricing pricing;
  private final BusinessClock clock;

  public CatalogController(Db db, Pricing pricing, BusinessClock clock) {
    this.db = db;
    this.pricing = pricing;
    this.clock = clock;
  }

  @GetMapping("/api/health")
  public Map<String, Object> health() {
    db.jdbc.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "UP", "today", clock.today().toString());
  }

  @GetMapping("/api/catalog/hotels")
  public List<Map<String, Object>> hotels() {
    return db.rows("SELECT * FROM hotel ORDER BY id");
  }

  @GetMapping("/api/catalog/hotels/{id}")
  public Map<String, Object> hotel(@PathVariable long id) {
    Map<String, Object> hotel = db.one("SELECT * FROM hotel WHERE id=?", id);
    hotel.put(
        "roomTypes",
        db.rows("SELECT * FROM room_type WHERE hotel_id=? AND active ORDER BY id", id));
    return hotel;
  }

  @GetMapping("/api/catalog/search")
  public List<Map<String, Object>> search(@RequestParam Map<String, String> query) {
    LocalDate start = Input.date(query, "start");
    LocalDate end = Input.date(query, "end");
    long nights = ChronoUnit.DAYS.between(start, end);
    Input.require(
        nights >= 1
            && nights <= 30
            && !start.isBefore(clock.today())
            && !end.isAfter(clock.today().plusDays(365)),
        "请选择未来一年内1—30晚的有效行程");
    int guests = (int) Input.number(query, "guests", 1, 30);
    long budget =
        query.getOrDefault("budget", "").isBlank()
            ? Long.MAX_VALUE
            : Input.number(query, "budget", 1, 1000000000);
    String campus = query.getOrDefault("campus", "");
    String type = query.getOrDefault("type", "");
    List<Map<String, Object>> rows =
        db.rows(
            """
SELECT r.id,r.hotel_id,r.name,r.capacity,r.description,r.image,h.name AS hotel_name,h.campus,
  MIN(CASE WHEN i.stop_sale OR i.emergency_stop THEN 0 ELSE i.capacity-COALESCE(a.used,0) END) AS available,
  SUM(i.online_price) AS per_room_total, COUNT(i.stay_date) AS nights
FROM room_type r JOIN hotel h ON h.id=r.hotel_id
JOIN inventory_day i ON i.room_type_id=r.id AND i.stay_date>=? AND i.stay_date<?
LEFT JOIN (SELECT b.room_type_id,n.stay_date,SUM(n.rooms) AS used FROM booking_night n
  JOIN booking b ON b.id=n.booking_id WHERE n.allocated AND n.stay_date>=? AND n.stay_date<?
  GROUP BY b.room_type_id,n.stay_date) a ON a.room_type_id=r.id AND a.stay_date=i.stay_date
WHERE r.active AND (?='' OR h.campus=?) AND (?='' OR r.name=?)
GROUP BY r.id,h.id ORDER BY h.id,r.id
""",
            start,
            end,
            start,
            end,
            campus,
            campus,
            type,
            type);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      long rooms = (guests + Db.value(row, "capacity") - 1) / Db.value(row, "capacity");
      long total = rooms * Db.value(row, "per_room_total");
      if (Db.value(row, "nights") == nights
          && Db.value(row, "available") >= rooms
          && total <= budget) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        item.put("rooms", rooms);
        item.put("total", total);
        result.add(item);
      }
    }
    return result;
  }

  @PostMapping("/api/quotes")
  public Map<String, Object> quote(
      @AuthenticationPrincipal Actor actor, @RequestBody Map<String, Object> body) {
    return pricing.quote(actor, body);
  }
}
