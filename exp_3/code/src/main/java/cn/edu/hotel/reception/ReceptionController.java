package cn.edu.hotel.reception;

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
@RequestMapping("/api/reception")
public class ReceptionController {
  private final ReceptionService service;

  public ReceptionController(ReceptionService service) {
    this.service = service;
  }

  @GetMapping("/rooms")
  public Map<String, Object> rooms(@AuthenticationPrincipal Actor actor) {
    return service.rooms(actor);
  }

  @GetMapping("/bookings/{id}/guests")
  public Map<String, Object> guests(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return service.guests(actor, id);
  }

  @GetMapping("/bookings/{id}/checkout")
  public Map<String, Object> preview(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
    return service.checkoutPreview(actor, id);
  }

  @GetMapping("/bookings/{id}/extend")
  public Map<String, Object> extension(
      @AuthenticationPrincipal Actor actor, @PathVariable long id, @RequestParam LocalDate end) {
    return service.extensionPreview(actor, id, end);
  }

  @PostMapping("/bookings/{id}/checkin")
  public Map<String, Object> checkIn(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.checkIn(actor, id, key, body);
  }

  @PostMapping("/bookings/{id}/checkout")
  public Map<String, Object> checkout(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.checkout(actor, id, key, body);
  }

  @PostMapping("/bookings/{id}/extend")
  public Map<String, Object> extend(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.extend(actor, id, key, body);
  }

  @PostMapping("/rooms/{id}/clean")
  public Map<String, Object> clean(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key) {
    return service.clean(actor, id, key);
  }

  @PostMapping("/bookings/{id}/requests")
  public Map<String, Object> request(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.request(actor, id, key, body);
  }

  @PostMapping("/requests/{id}/resolve")
  public Map<String, Object> resolve(
      @AuthenticationPrincipal Actor actor,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return service.resolveRequest(actor, id, key, body);
  }
}
