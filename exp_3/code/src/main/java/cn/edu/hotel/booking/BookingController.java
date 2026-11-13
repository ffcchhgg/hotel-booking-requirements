package cn.edu.hotel.booking;

import cn.edu.hotel.identity.Actor;
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
@RequestMapping("/api")
public class BookingController {
  private final BookingService bookings;

  public BookingController(BookingService bookings) {
    this.bookings = bookings;
  }

  @GetMapping("/bookings")
  public Map<String, Object> list(
      @AuthenticationPrincipal Actor actor, @RequestParam Map<String, String> query) {
    return bookings.list(actor, query);
  }

  @GetMapping("/bookings/{id}")
  public Map<String, Object> detail(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return bookings.detail(actor, id);
  }

  @PostMapping("/bookings")
  public Map<String, Object> create(
      @AuthenticationPrincipal Actor actor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return bookings.create(actor, key, body);
  }

  @PostMapping("/bookings/{id}/confirm")
  public Map<String, Object> confirm(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return bookings.confirm(actor, id, key, body);
  }

  @PostMapping("/bookings/{id}/cancel")
  public Map<String, Object> cancel(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return bookings.cancel(actor, id, key, body);
  }

  @PostMapping("/bookings/{id}/modify")
  public Map<String, Object> modify(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return bookings.beginModification(actor, id, key, body);
  }

  @PostMapping("/modifications/{id}/complete")
  public Map<String, Object> complete(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return bookings.finishModification(actor, id, key, body);
  }
}
