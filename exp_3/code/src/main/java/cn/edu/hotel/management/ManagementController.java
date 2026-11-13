package cn.edu.hotel.management;

import cn.edu.hotel.core.Db;
import cn.edu.hotel.identity.Actor;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manage")
public class ManagementController {
  private final ManagementService service;
  private final IncidentService incidents;
  private final Db db;

  public ManagementController(ManagementService service, IncidentService incidents, Db db) {
    this.service = service;
    this.incidents = incidents;
    this.db = db;
  }

  @GetMapping("/catalog")
  public Object catalog(
      @AuthenticationPrincipal Actor actor,
      @RequestParam long hotelId,
      @RequestParam LocalDate start,
      @RequestParam LocalDate end) {
    return service.catalog(actor, hotelId, start, end);
  }

  @GetMapping("/changes")
  public Object changes(@AuthenticationPrincipal Actor actor, @RequestParam long hotelId) {
    return service.changes(actor, hotelId);
  }

  @PostMapping("/changes")
  public Object submit(
      @AuthenticationPrincipal Actor actor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.submit(actor, key, body);
  }

  @PostMapping("/changes/{id}/review")
  public Object review(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.review(actor, id, key, body);
  }

  @PostMapping("/hotels/{id}/fee")
  public Object fee(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.fee(actor, id, key, body);
  }

  @GetMapping("/logs")
  public Object logs(
      @AuthenticationPrincipal Actor actor,
      @RequestParam long hotelId,
      @RequestParam(defaultValue = "1") int page) {
    return service.logs(actor, hotelId, page);
  }

  @GetMapping("/incidents/preview")
  public Object preview(
      @AuthenticationPrincipal Actor actor, @RequestParam Map<String, String> query) {
    return incidents.preview(actor, query);
  }

  @GetMapping("/incidents")
  public Object incidents(@AuthenticationPrincipal Actor actor, @RequestParam long hotelId) {
    return incidents.list(actor, hotelId);
  }

  @PostMapping("/incidents")
  public Object incident(
      @AuthenticationPrincipal Actor actor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return incidents.open(actor, key, body);
  }

  @PostMapping("/incidents/{id}/cases/{booking}")
  public Object resolve(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @PathVariable long booking,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return incidents.resolve(actor, id, booking, key, body);
  }

  @PostMapping("/incidents/{id}/close")
  public Object close(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return incidents.close(actor, id, key, body);
  }

  @GetMapping("/statistics")
  public Object statistics(
      @AuthenticationPrincipal Actor actor,
      @RequestParam long hotelId,
      @RequestParam LocalDate start,
      @RequestParam LocalDate end) {
    actor.require("ADMIN");
    service.dateRange(start, end, 366, false);
    db.one("SELECT id FROM hotel WHERE id=?", hotelId);
    return db.transaction.execute(
        status -> {
          // Use the same hotel lock as writers so all aggregates describe one committed state.
          db.lockHotel(hotelId);
          Map<String, Object> money =
              db.one(
                  """
SELECT COALESCE(SUM(m.amount) FILTER(WHERE m.kind<>'REFUND'),0) AS gross,
  COALESCE(SUM(m.amount) FILTER(WHERE m.kind='REFUND'),0) AS refunds,
  COALESCE(SUM(CASE WHEN m.kind='REFUND' THEN -m.amount ELSE m.amount END),0) AS net
FROM money_entry m JOIN booking b ON b.id=m.booking_id WHERE b.hotel_id=?
AND (m.created_at AT TIME ZONE 'Asia/Shanghai')::date>=?
AND (m.created_at AT TIME ZONE 'Asia/Shanghai')::date<?
""",
                  hotelId,
                  start,
                  end);
          return Map.of(
              "money",
              money,
              "orders",
              db.rows(
                  "SELECT status,COUNT(*) AS count FROM booking WHERE hotel_id=? AND (created_at AT"
                      + " TIME ZONE 'Asia/Shanghai')::date>=? AND (created_at AT TIME ZONE"
                      + " 'Asia/Shanghai')::date<? GROUP BY status ORDER BY status",
                  hotelId,
                  start,
                  end),
              "rooms",
              db.rows(
                  "SELECT state,COUNT(*) AS count FROM physical_room WHERE hotel_id=? GROUP BY"
                      + " state ORDER BY state",
                  hotelId),
              "daily",
              db.rows(
                  """
SELECT d.day::date AS day,
  (SELECT COALESCE(SUM(i.capacity),0) FROM inventory_day i JOIN room_type r ON r.id=i.room_type_id
    WHERE r.hotel_id=? AND i.stay_date=d.day::date) AS capacity,
  (SELECT COUNT(*) FROM stay s JOIN booking b ON b.id=s.booking_id WHERE b.hotel_id=?
    AND (s.started_at AT TIME ZONE 'Asia/Shanghai')::date<=d.day::date
    AND COALESCE((s.ended_at AT TIME ZONE 'Asia/Shanghai')::date,b.check_out)>d.day::date) AS occupied
FROM generate_series(?::date,?::date-1,interval '1 day') d(day) ORDER BY d.day
""",
                  hotelId,
                  hotelId,
                  start,
                  end),
              "definition",
              "流水按北京时间发生日汇总；收入含模拟预订、续住及超时收费，退款另列。入住率按实际办理的住宿间夜 / 当日配置总房量；未来日期仅供排班参考，不作为实际经营业绩。");
        });
  }
}
