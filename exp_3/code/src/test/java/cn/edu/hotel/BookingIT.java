package cn.edu.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.hotel.booking.BookingService;
import cn.edu.hotel.catalog.Pricing;
import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.FaultProbe;
import cn.edu.hotel.identity.Actor;
import cn.edu.hotel.management.IncidentService;
import cn.edu.hotel.management.ManagementService;
import cn.edu.hotel.reception.ReceptionService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"hotel.demo=false", "hotel.jobs-enabled=false"})
@AutoConfigureMockMvc
class BookingIT {
  @Autowired Db db;
  @Autowired BookingService bookings;
  @Autowired Pricing pricing;
  @Autowired ReceptionService reception;
  @Autowired ManagementService management;
  @Autowired IncidentService incidents;
  @Autowired MockMvc mvc;
  @MockitoBean BusinessClock clock;
  @MockitoBean FaultProbe faults;
  private final LocalDate today = LocalDate.of(2026, 11, 13);
  private Actor guest;
  private Actor other;
  private Actor staff;
  private Actor outsider;
  private Actor admin;
  private long hotel;
  private long type;

  @BeforeEach
  void setup() {
    // Destructive fixture operations are restricted to an explicitly separate test database.
    assertTrue(db.jdbc.queryForObject("SELECT current_database()", String.class).endsWith("_test"));
    reset(clock, faults);
    when(clock.now()).thenReturn(Instant.parse("2026-11-13T02:00:00Z"));
    when(clock.today()).thenReturn(today);
    when(clock.at(
            org.mockito.ArgumentMatchers.any(LocalDate.class),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            call ->
                ((LocalDate) call.getArgument(0))
                    .atTime((Integer) call.getArgument(1), 0)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toInstant());
    db.jdbc.execute("TRUNCATE hotel,app_user RESTART IDENTITY CASCADE");
    hotel = db.insert("INSERT INTO hotel(name,campus) VALUES('测试酒店','仙林')");
    long otherHotel = db.insert("INSERT INTO hotel(name,campus) VALUES('其他酒店','鼓楼')");
    guest = actor("guest", "CUSTOMER", null);
    other = actor("other", "CUSTOMER", null);
    staff = actor("staff", "STAFF", hotel);
    outsider = actor("outsider", "STAFF", otherHotel);
    admin = actor("admin", "ADMIN", null);
    type = db.insert("INSERT INTO room_type(hotel_id,name,capacity) VALUES(?,'标准双床房',2)", hotel);
    db.jdbc.update(
        "INSERT INTO inventory_day(room_type_id,stay_date,capacity,public_price,online_price)"
            + " SELECT ?,d::date,2,10001,9001 FROM generate_series(?::date,?::date,interval '1"
            + " day') d",
        type,
        today.minusDays(2),
        today.plusDays(40));
    for (int i = 1; i <= 2; i++) {
      db.jdbc.update(
          "INSERT INTO physical_room(hotel_id,room_type_id,number,state) VALUES(?,?,?,'READY')",
          hotel,
          type,
          "20" + i);
    }
    db.jdbc.update(
        "INSERT INTO fee_policy(hotel_id,hourly_cents,created_by,created_at) VALUES(?,5000,?,?)",
        hotel,
        admin.id(),
        Db.time(clock.now()));
  }

  private Actor actor(String name, String role, Long scope) {
    long id =
        db.insert(
            "INSERT INTO app_user(username,password_hash,display_name,role,hotel_id)"
                + " VALUES(?,'unused',?,?,?)",
            name,
            name,
            role,
            scope);
    return Actor.from(db.one("SELECT * FROM app_user WHERE id=?", id));
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private Map<String, Object> selection(
      LocalDate start, int nights, int rooms, int guests, boolean offline) {
    return Map.of(
        "roomTypeId",
        type,
        "start",
        start.toString(),
        "end",
        start.plusDays(nights).toString(),
        "rooms",
        rooms,
        "guests",
        guests,
        "offline",
        offline);
  }

  private Map<String, Object> payBody(String quote) {
    return Map.of(
        "quoteId",
        quote,
        "accepted",
        true,
        "contact",
        "测试住客",
        "phone",
        "13800000000",
        "studentVerified",
        true);
  }

  private long create(Actor actor, LocalDate start, int nights, int rooms) {
    String quote =
        pricing
            .quote(actor, selection(start, nights, rooms, rooms, actor.is("STAFF")))
            .get("id")
            .toString();
    return Db.value(bookings.create(actor, key(), payBody(quote)), "id");
  }

  private Map<String, Object> order(long id) {
    return db.one("SELECT * FROM booking WHERE id=?", id);
  }

  private void confirm(long id) {
    bookings.confirm(staff, id, key(), Map.of("version", order(id).get("version")));
  }

  private long count(String table) {
    return db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private Map<String, Object> cancelBody(long id) {
    Map<String, Object> row = order(id);
    return Map.of(
        "version", row.get("version"), "expectedRefund", bookings.refundable(row, clock.now()));
  }

  private void time(String instant) {
    when(clock.now()).thenReturn(Instant.parse(instant));
    when(clock.today())
        .thenReturn(
            Instant.parse(instant).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate());
  }

  private void checkIn(long id) {
    long room =
        Db.value(
            db.one("SELECT id FROM physical_room WHERE state='READY' ORDER BY id LIMIT 1"), "id");
    reception.checkIn(
        staff,
        id,
        key(),
        Map.of(
            "version",
            order(id).get("version"),
            "identityVerified",
            true,
            "assignments",
            List.of(
                Map.of(
                    "roomId",
                    room,
                    "guests",
                    List.of(
                        Map.of(
                            "name", "测试住客", "phone", "13800000000", "document", "TEST000001"))))));
  }

  @Test
  void onlinePendingDoesNotAllocateAndConfirmationAllocatesEveryNight() {
    long id = create(guest, today, 3, 1);
    assertEquals("PENDING", order(id).get("status"));
    assertEquals(
        2, Db.value(pricing.availability(type, today, today.plusDays(3)).getFirst(), "remaining"));
    confirm(id);
    assertEquals(
        3,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM booking_night WHERE allocated", Long.class));
  }

  @Test
  void earlierPaymentHasPriorityButIndependentRemainingRoomsCanBeConfirmed() {
    long first = create(guest, today, 1, 2);
    long second = create(other, today, 1, 1);
    assertThrows(BusinessException.class, () -> confirm(second));
    confirm(first);
    assertEquals("CANCELLED", order(second).get("status"));
    assertEquals(
        1,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void offlineCanConsumeRoomsBeforeOnlineConfirmation() {
    long online = create(guest, today, 2, 2);
    create(staff, today, 2, 2);
    assertEquals("CANCELLED", order(online).get("status"));
    assertEquals(
        0, Db.value(pricing.availability(type, today, today.plusDays(2)).getFirst(), "remaining"));
  }

  @Test
  void noDuplicatePaymentForIdenticalRequestAndMismatchedReuseRejected() {
    String quote = pricing.quote(guest, selection(today, 1, 1, 1, false)).get("id").toString();
    String key = key();
    Map<String, Object> body = payBody(quote);
    Map<String, Object> first = bookings.create(guest, key, body);
    Map<String, Object> again = bookings.create(guest, key, body);
    assertEquals(Db.value(first, "id"), Db.value(again, "id"));
    assertEquals(1, count("money_entry"));
    Map<String, Object> changed = new HashMap<>(body);
    changed.put("contact", "不同联系人");
    assertThrows(BusinessException.class, () -> bookings.create(guest, key, changed));
  }

  @Test
  void quoteCannotBeReusedUnderAnotherKey() {
    String quote = pricing.quote(guest, selection(today, 1, 1, 1, false)).get("id").toString();
    bookings.create(guest, key(), payBody(quote));
    assertThrows(BusinessException.class, () -> bookings.create(guest, key(), payBody(quote)));
    assertEquals(1, count("booking"));
  }

  @Test
  void failedPaymentCreatesNeitherOrderNorMoney() {
    String quote = pricing.quote(guest, selection(today, 1, 1, 1, false)).get("id").toString();
    Map<String, Object> body = new HashMap<>(payBody(quote));
    body.put("simulateFailure", true);
    assertEquals(true, bookings.create(guest, key(), body).get("paymentFailed"));
    assertEquals(0, count("booking"));
    assertEquals(0, count("money_entry"));
  }

  @Test
  void createRollsBackIfFailureOccursAfterNightInsert() {
    doThrow(new IllegalStateException("injected")).when(faults).checkpoint("after-order-nights");
    assertThrows(IllegalStateException.class, () -> create(guest, today, 3, 1));
    assertEquals(0, count("booking"));
    assertEquals(0, count("booking_night"));
    assertEquals(0, count("request_record"));
  }

  @Test
  void allocationRollsBackAllNightsOnFailure() {
    long id = create(guest, today, 3, 1);
    doThrow(new IllegalStateException("injected"))
        .when(faults)
        .checkpoint("after-first-allocation");
    assertThrows(IllegalStateException.class, () -> confirm(id));
    assertEquals("PENDING", order(id).get("status"));
    assertEquals(
        0,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM booking_night WHERE allocated", Long.class));
  }

  @Test
  void refundFailureKeepsOrderAndInventoryIntact() {
    long id = create(guest, today, 2, 1);
    confirm(id);
    doThrow(new IllegalStateException("injected")).when(faults).checkpoint("after-refund");
    assertThrows(
        IllegalStateException.class, () -> bookings.cancel(guest, id, key(), cancelBody(id)));
    assertEquals("CONFIRMED", order(id).get("status"));
    assertEquals(
        0,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
    assertEquals(
        2,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM booking_night WHERE allocated", Long.class));
  }

  @Test
  void cancellationAtExactly18ReleasesRoomsWithoutRefund() {
    long id = create(guest, today, 1, 1);
    confirm(id);
    time("2026-11-13T10:00:00Z");
    assertEquals(0, bookings.refundable(order(id), clock.now()));
    bookings.cancel(guest, id, key(), cancelBody(id));
    assertEquals("CANCELLED", order(id).get("status"));
    assertEquals(
        0,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
    assertEquals(
        2, Db.value(pricing.availability(type, today, today.plusDays(1)).getFirst(), "remaining"));
  }

  @Test
  void cancellationOneSecondBefore18RefundsAll() {
    long id = create(guest, today, 1, 1);
    confirm(id);
    time("2026-11-13T09:59:59Z");
    bookings.cancel(guest, id, key(), cancelBody(id));
    assertEquals(
        9001,
        db.jdbc.queryForObject("SELECT amount FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void pendingAlwaysRefundsEvenAfter18() {
    time("2026-11-13T10:00:00Z");
    long id = create(guest, today, 1, 1);
    bookings.cancel(guest, id, key(), cancelBody(id));
    assertEquals(
        9001,
        db.jdbc.queryForObject("SELECT amount FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void timeoutBoundaryIsExactly30MinutesAndTaskIsIdempotent() {
    long id = create(guest, today, 1, 1);
    time("2026-11-13T02:29:59Z");
    bookings.processDue();
    assertEquals("PENDING", order(id).get("status"));
    time("2026-11-13T02:30:00Z");
    bookings.processDue();
    bookings.processDue();
    assertEquals("CANCELLED", order(id).get("status"));
    assertEquals(
        1,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void confirmedNoShowRetainsEveryNightUntilOriginalCheckoutNoon() {
    long id = create(guest, today, 2, 1);
    confirm(id);
    time("2026-11-15T03:59:59Z");
    bookings.processDue();
    assertEquals("CONFIRMED", order(id).get("status"));
    assertEquals(
        2,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM booking_night WHERE allocated", Long.class));
    time("2026-11-15T04:00:00Z");
    bookings.processDue();
    assertEquals("NO_SHOW", order(id).get("status"));
    assertEquals(
        0,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void overdueConfirmedOrderCannotBeCancelledOrModifiedBeforeSchedulerRuns() {
    long id = create(guest, today, 1, 1);
    confirm(id);
    time("2026-11-14T04:00:00Z");
    String quote =
        pricing.quote(guest, selection(today.plusDays(2), 1, 1, 1, false)).get("id").toString();
    Map<String, Object> body = new HashMap<>(cancelBody(id));
    body.put("quoteId", quote);
    body.put("riskAccepted", true);

    assertThrows(BusinessException.class, () -> bookings.cancel(guest, id, key(), cancelBody(id)));
    assertThrows(BusinessException.class, () -> bookings.beginModification(guest, id, key(), body));
    assertEquals("CONFIRMED", order(id).get("status"));
    assertEquals(0, count("modification"));
    assertEquals(1, count("money_entry"));

    bookings.processDue();
    assertEquals("NO_SHOW", order(id).get("status"));
    assertEquals(1, count("money_entry"));
  }

  @Test
  void modificationFailureDoesNotResurrectOriginalBooking() {
    long id = create(guest, today, 1, 1);
    confirm(id);
    String quote =
        pricing.quote(guest, selection(today.plusDays(1), 2, 1, 1, false)).get("id").toString();
    Map<String, Object> body = new HashMap<>(cancelBody(id));
    body.put("quoteId", quote);
    body.put("riskAccepted", true);
    long modification = Db.value(bookings.beginModification(guest, id, key(), body), "id");
    bookings.finishModification(
        guest, modification, key(), Map.of("accepted", true, "simulateFailure", true));
    assertEquals("CANCELLED", order(id).get("status"));
    assertEquals(
        "FAILED", db.one("SELECT state FROM modification WHERE id=?", modification).get("state"));
    assertEquals(1, count("booking"));
  }

  @Test
  void modificationCanResumeAfterFailureWithoutRefundingAgain() {
    long id = create(guest, today, 1, 1);
    String quote =
        pricing.quote(guest, selection(today.plusDays(1), 1, 1, 1, false)).get("id").toString();
    Map<String, Object> body = new HashMap<>(cancelBody(id));
    body.put("quoteId", quote);
    body.put("riskAccepted", true);
    long modification = Db.value(bookings.beginModification(guest, id, key(), body), "id");
    bookings.finishModification(
        guest, modification, key(), Map.of("accepted", true, "simulateFailure", true));
    long newer =
        Db.value(
            bookings.finishModification(guest, modification, key(), Map.of("accepted", true)),
            "id");
    assertNotEquals(id, newer);
    assertEquals(id, Db.value(order(newer), "parent_id"));
    assertEquals(
        1,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void priceChangesRequireRequoteAndOldPaidPriceIsImmutable() {
    long id = create(guest, today, 1, 1);
    String quote = pricing.quote(guest, selection(today, 1, 1, 1, false)).get("id").toString();
    db.jdbc.update("UPDATE inventory_day SET online_price=99999 WHERE room_type_id=?", type);
    assertThrows(BusinessException.class, () -> bookings.create(guest, key(), payBody(quote)));
    confirm(id);
    assertEquals(9001, Db.value(order(id), "amount"));
    assertEquals(
        9001,
        db.jdbc.queryForObject(
            "SELECT unit_price FROM booking_night WHERE booking_id=?", Long.class, id));
  }

  @Test
  void studentDiscountIsOfflineOnlyAndRoundsToCents() {
    Map<String, Object> selected = new HashMap<>(selection(today, 1, 1, 1, true));
    selected.put("student", true);
    assertEquals(8001, Db.value(pricing.quote(staff, selected), "amount"));
    selected.put("offline", false);
    assertThrows(BusinessException.class, () -> pricing.quote(guest, selected));
  }

  @Test
  void multiNightAvailabilityUsesMinimumNotFirstNight() {
    db.jdbc.update(
        "UPDATE inventory_day SET capacity=0 WHERE room_type_id=? AND stay_date=?",
        type,
        today.plusDays(1));
    assertThrows(BusinessException.class, () -> create(guest, today, 3, 1));
    assertEquals(0, count("booking"));
  }

  @Test
  void datesAreHalfOpenSoNextCheckoutDayCanBeBooked() {
    create(staff, today, 1, 2);
    long id = create(staff, today.plusDays(1), 1, 2);
    assertEquals("CONFIRMED", order(id).get("status"));
  }

  @Test
  void outOfScopeUsersCannotReadOrMutateOrders() {
    long id = create(guest, today, 1, 1);
    assertThrows(BusinessException.class, () -> bookings.detail(other, id));
    assertThrows(BusinessException.class, () -> bookings.detail(outsider, id));
    assertThrows(
        BusinessException.class, () -> bookings.confirm(admin, id, key(), Map.of("version", 1)));
    assertThrows(BusinessException.class, () -> bookings.cancel(staff, id, key(), cancelBody(id)));
  }

  @Test
  void staleVersionsDoNotChangeMoneyOrInventory() {
    long id = create(guest, today, 1, 1);
    confirm(id);
    assertThrows(
        BusinessException.class,
        () -> bookings.cancel(guest, id, key(), Map.of("version", 1, "expectedRefund", 9001)));
    assertEquals("CONFIRMED", order(id).get("status"));
  }

  @Test
  void checkInMasksDocumentsAndPreventsNormalCancellation() {
    long id = create(staff, today, 1, 1);
    checkIn(id);
    assertEquals("CHECKED_IN", order(id).get("status"));
    assertTrue(reception.guests(staff, id).toString().contains("******0001"));
    assertFalse(db.rows("SELECT summary FROM operation_log").toString().contains("TEST000001"));
    assertThrows(BusinessException.class, () -> reception.guests(outsider, id));
  }

  @Test
  void incompleteGuestRegistrationRollsBackRoomAssignment() {
    long id = create(staff, today, 1, 2);
    assertThrows(BusinessException.class, () -> checkIn(id));
    assertEquals(0, count("stay"));
    assertEquals(
        2,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM physical_room WHERE state='READY'", Long.class));
  }

  @Test
  void earlyCheckoutRequiresManualProcess() {
    long id = create(staff, today, 2, 1);
    checkIn(id);
    assertThrows(
        BusinessException.class,
        () ->
            reception.checkout(
                staff, id, key(), Map.of("version", order(id).get("version"), "expectedFee", 0)));
    assertEquals("CHECKED_IN", order(id).get("status"));
  }

  @Test
  void checkoutChargesCeilingHoursAtSnapshottedRateThenRequiresCleaning() {
    long id = create(staff, today, 1, 1);
    checkIn(id);
    management.fee(admin, hotel, key(), Map.of("hourlyRate", 9000));
    time("2026-11-14T04:00:01Z");
    assertEquals(5000L, reception.checkoutPreview(staff, id).get("fee"));
    reception.checkout(
        staff,
        id,
        key(),
        Map.of("version", order(id).get("version"), "expectedFee", 5000, "paymentConfirmed", true));
    long room = Db.value(db.one("SELECT id FROM physical_room WHERE state='DIRTY'"), "id");
    reception.clean(staff, room, key());
    assertEquals("READY", db.one("SELECT state FROM physical_room WHERE id=?", room).get("state"));
  }

  @Test
  void extensionAddsOnlyExtraNightsAndAdditionalPayment() {
    long id = create(staff, today, 1, 1);
    checkIn(id);
    Map<String, Object> quote = reception.extensionPreview(staff, id, today.plusDays(3));
    Map<String, Object> body = new HashMap<>(quote);
    body.put("paymentConfirmed", true);
    reception.extend(staff, id, key(), body);
    assertEquals(3, count("booking_night"));
    assertEquals(30003, Db.value(order(id), "amount"));
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM money_entry WHERE kind='EXTENSION'", Long.class));
  }

  @Test
  void extensionAfterDeadlineDoesNotSilentlyChangeTheOriginalOrder() {
    long id = create(staff, today, 1, 1);
    checkIn(id);
    time("2026-11-14T04:00:00Z");
    assertThrows(
        BusinessException.class, () -> reception.extensionPreview(staff, id, today.plusDays(2)));
    assertEquals(1, count("booking_night"));
  }

  private Map<String, Object> change() {
    return Map.of(
        "kind",
        "INVENTORY",
        "targetId",
        type,
        "start",
        today.toString(),
        "end",
        today.plusDays(2).toString(),
        "capacity",
        2,
        "publicPrice",
        12000,
        "onlinePrice",
        11000,
        "stopSale",
        false);
  }

  @Test
  void changeIsNotPublishedUntilApprovedAndRejectionPreservesLiveData() {
    long id = Db.value(management.submit(staff, key(), change()), "id");
    assertEquals(
        9001,
        Db.value(pricing.availability(type, today, today.plusDays(1)).getFirst(), "online_price"));
    management.review(admin, id, key(), Map.of("approve", false, "note", "资料需补充"));
    assertEquals(
        9001,
        Db.value(pricing.availability(type, today, today.plusDays(1)).getFirst(), "online_price"));
    long newer = Db.value(management.submit(staff, key(), change()), "id");
    management.review(admin, newer, key(), Map.of("approve", true, "note", "已核对"));
    assertEquals(
        11000,
        Db.value(pricing.availability(type, today, today.plusDays(1)).getFirst(), "online_price"));
  }

  @Test
  void staleApprovalCannotOverwriteNewerPrices() {
    long first = Db.value(management.submit(staff, key(), change()), "id");
    long second = Db.value(management.submit(staff, key(), change()), "id");
    management.review(admin, first, key(), Map.of("approve", true, "note", "同意"));
    assertThrows(
        BusinessException.class,
        () -> management.review(admin, second, key(), Map.of("approve", true, "note", "同意")));
    assertEquals(
        "PENDING", db.one("SELECT status FROM change_request WHERE id=?", second).get("status"));
  }

  @Test
  void inventoryCannotBeReducedBelowExistingPromises() {
    create(staff, today, 1, 2);
    Map<String, Object> body = new HashMap<>(change());
    body.put("capacity", 1);
    assertThrows(BusinessException.class, () -> management.submit(staff, key(), body));
  }

  @Test
  void incidentRefundsUnarrivedAt18ButQueuesInHouseCasesAndOverlappingStops() {
    long unarrived = create(guest, today, 2, 1);
    confirm(unarrived);
    long inhouse = create(staff, today, 2, 1);
    checkIn(inhouse);
    time("2026-11-13T10:00:00Z");
    Map<String, Object> body =
        Map.of(
            "roomTypeId",
            type,
            "start",
            today.toString(),
            "end",
            today.plusDays(1).toString(),
            "reason",
            "供水故障",
            "confirmed",
            true);
    long first = Db.value(incidents.open(staff, key(), body), "id");
    long second = Db.value(incidents.open(staff, key(), body), "id");
    assertEquals("CANCELLED", order(unarrived).get("status"));
    assertEquals("CHECKED_IN", order(inhouse).get("status"));
    assertEquals(
        18002,
        db.jdbc.queryForObject("SELECT amount FROM money_entry WHERE kind='REFUND'", Long.class));
    assertThrows(
        BusinessException.class,
        () -> incidents.close(staff, first, key(), Map.of("restored", true)));
    incidents.resolve(staff, first, inhouse, key(), Map.of("note", "已联系住客并记录安排"));
    incidents.close(staff, first, key(), Map.of("restored", true));
    assertEquals(
        true,
        pricing.availability(type, today, today.plusDays(1)).getFirst().get("emergency_stop"));
    incidents.resolve(staff, second, inhouse, key(), Map.of("note", "已处理"));
    incidents.close(staff, second, key(), Map.of("restored", true));
    assertEquals(
        false,
        pricing.availability(type, today, today.plusDays(1)).getFirst().get("emergency_stop"));
  }

  @Test
  void twentyConcurrentConfirmationsCannotOversell() throws Exception {
    List<Long> orders = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      orders.add(create(guest, today, 2, 1));
    }
    CountDownLatch ready = new CountDownLatch(20);
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(20)) {
      List<Future<?>> futures = new ArrayList<>();
      for (long id : orders) {
        futures.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  try {
                    start.await();
                    confirm(id);
                  } catch (BusinessException expected) {
                    assertTrue(List.of(400, 409).contains(expected.status()));
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                  }
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (Future<?> future : futures) {
        future.get(20, TimeUnit.SECONDS);
      }
    }
    assertEquals(
        2,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status='CONFIRMED'", Long.class));
    assertEquals(
        0, Db.value(pricing.availability(type, today, today.plusDays(1)).getFirst(), "remaining"));
    assertEquals(
        18,
        db.jdbc.queryForObject("SELECT COUNT(*) FROM money_entry WHERE kind='REFUND'", Long.class));
  }

  @Test
  void concurrentIdenticalRequestsProduceOneOrder() throws Exception {
    String quote = pricing.quote(guest, selection(today, 1, 1, 1, false)).get("id").toString();
    String key = key();
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(8)) {
      List<Future<Long>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        futures.add(
            workers.submit(
                () -> {
                  start.await();
                  return Db.value(bookings.create(guest, key, payBody(quote)), "id");
                }));
      }
      start.countDown();
      long expected = futures.getFirst().get(10, TimeUnit.SECONDS);
      for (Future<Long> future : futures) {
        assertEquals(expected, future.get(10, TimeUnit.SECONDS));
      }
    }
    assertEquals(1, count("booking"));
    assertEquals(1, count("money_entry"));
  }

  @Test
  void validSearchAndStatisticsQueriesRunAgainstPostgres() throws Exception {
    create(staff, today, 1, 1);
    mvc.perform(
            get("/api/catalog/search")
                .param("start", today.toString())
                .param("end", today.plusDays(1).toString())
                .param("guests", "1"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/manage/statistics")
                .with(user(admin))
                .param("hotelId", String.valueOf(hotel))
                .param("start", today.toString())
                .param("end", today.plusDays(1).toString()))
        .andExpect(status().isOk());
  }

  @Test
  void confirmationAtTimeoutUsesCancellationEvenWhenSchedulerHasNotRun() {
    long id = create(guest, today, 1, 1);
    time("2026-11-13T02:30:00Z");
    Map<String, Object> result = bookings.confirm(staff, id, key(), Map.of("version", 1));
    assertEquals("CANCELLED", result.get("status"));
    assertEquals("TIMEOUT", order(id).get("reason"));
  }

  @Test
  void approvalRechecksOrdersCreatedAfterTheRequest() {
    Map<String, Object> body = new HashMap<>(change());
    body.put("capacity", 1);
    long id = Db.value(management.submit(staff, key(), body), "id");
    create(staff, today, 1, 2);
    assertThrows(
        BusinessException.class,
        () -> management.review(admin, id, key(), Map.of("approve", true, "note", "已核对")));
    assertEquals(
        2, Db.value(pricing.availability(type, today, today.plusDays(1)).getFirst(), "capacity"));
  }

  @Test
  void quoteExpiresWithoutCreatingPayment() {
    String quote = pricing.quote(guest, selection(today, 1, 1, 1, false)).get("id").toString();
    time("2026-11-13T02:10:00Z");
    assertThrows(BusinessException.class, () -> bookings.create(guest, key(), payBody(quote)));
    assertEquals(0, count("money_entry"));
  }

  @Test
  void disallowedTransitionsCannotReviveCancelledOrders() {
    long id = create(guest, today, 1, 1);
    bookings.cancel(guest, id, key(), cancelBody(id));
    assertThrows(BusinessException.class, () -> confirm(id));
    assertThrows(BusinessException.class, () -> checkIn(id));
    assertThrows(
        BusinessException.class,
        () ->
            reception.checkout(
                staff, id, key(), Map.of("version", order(id).get("version"), "expectedFee", 0)));
    assertEquals("CANCELLED", order(id).get("status"));
    assertEquals(2, count("money_entry"));
  }

  @Test
  void dirtyRoomAndMissingProxyVerificationRejectCheckinWithoutSideEffects() {
    long id = create(staff, today, 1, 1);
    long room = Db.value(db.one("SELECT id FROM physical_room ORDER BY id LIMIT 1"), "id");
    Map<String, Object> body =
        new HashMap<>(
            Map.of(
                "version",
                1,
                "identityVerified",
                true,
                "proxyBooking",
                true,
                "assignments",
                List.of(
                    Map.of(
                        "roomId",
                        room,
                        "guests",
                        List.of(
                            Map.of(
                                "name",
                                "测试",
                                "phone",
                                "13800000000",
                                "document",
                                "TEST000001"))))));
    assertThrows(BusinessException.class, () -> reception.checkIn(staff, id, key(), body));
    body.put("phoneVerified", true);
    db.jdbc.update("UPDATE physical_room SET state='DIRTY' WHERE id=?", room);
    assertThrows(BusinessException.class, () -> reception.checkIn(staff, id, key(), body));
    assertEquals(0, count("stay"));
    reception.clean(staff, room, key());
    reception.checkIn(staff, id, key(), body);
    assertEquals("CHECKED_IN", order(id).get("status"));
  }

  @Test
  void positiveFractionOfFirstOverdueSecondStillIncursAnHour() {
    long id = create(staff, today, 1, 1);
    checkIn(id);
    time("2026-11-14T04:00:00.001Z");
    assertEquals(5000L, reception.checkoutPreview(staff, id).get("fee"));
  }

  @Test
  void extensionRejectsOccupiedFutureNightsAndLeavesOldMoneyUnchanged() {
    long id = create(staff, today, 1, 1);
    checkIn(id);
    create(staff, today.plusDays(1), 1, 2);
    assertThrows(
        BusinessException.class, () -> reception.extensionPreview(staff, id, today.plusDays(2)));
    assertEquals(10001, Db.value(order(id), "amount"));
    assertEquals(today.plusDays(1), Db.date(order(id), "check_out"));
  }

  @Test
  void invalidImageContentAndCustomerUploadAreRejected() throws Exception {
    MockMultipartFile fake =
        new MockMultipartFile(
            "file",
            "fake.png",
            "image/png",
            "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    mvc.perform(multipart("/api/manage/images").file(fake).with(user(staff)).with(csrf()))
        .andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/manage/images").file(fake).with(user(guest)).with(csrf()))
        .andExpect(status().isForbidden());
    assertEquals(0, count("uploaded_image"));
  }

  @Test
  void imageStaysPrivateUntilItsHotelChangeIsApproved() throws Exception {
    var output = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        output);
    MockMultipartFile picture =
        new MockMultipartFile("file", "../photo.png", "image/png", output.toByteArray());
    String response =
        mvc.perform(multipart("/api/manage/images").file(picture).with(user(staff)).with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("url").asText();
    mvc.perform(get(url)).andExpect(status().isNotFound());
    mvc.perform(get(url).with(user(outsider))).andExpect(status().isNotFound());
    mvc.perform(get(url).with(user(staff))).andExpect(status().isOk());
    long change =
        Db.value(
            management.submit(
                staff,
                key(),
                Map.of(
                    "kind",
                    "HOTEL",
                    "targetId",
                    hotel,
                    "version",
                    1,
                    "name",
                    "新名称",
                    "description",
                    "测试资料",
                    "image",
                    url)),
            "id");
    management.review(admin, change, key(), Map.of("approve", true, "note", "图片已核对"));
    mvc.perform(get(url)).andExpect(status().isOk());
  }

  @Test
  void registrationStoresBcryptAndBadLoginDoesNotCreateIdentity() throws Exception {
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"username\":\"newguest\",\"password\":\"LongPassword123!\",\"displayName\":\"新顾客\"}"))
        .andExpect(status().isOk());
    Map<String, Object> user = db.one("SELECT * FROM app_user WHERE username='newguest'");
    assertEquals("CUSTOMER", user.get("role"));
    assertTrue(Db.string(user, "password_hash").startsWith("$2a$"));
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"newguest\",\"password\":\"wrongpassword\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void missingRequestKeyAndMalformedParametersAre400Not500() throws Exception {
    mvc.perform(
            post("/api/bookings")
                .with(user(guest))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/bookings/not-a-number").with(user(guest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void modificationWithUnavailableNewNightKeepsCancelledOriginalAndCanRetry() {
    long old = create(guest, today, 1, 1);
    String quote =
        pricing.quote(guest, selection(today.plusDays(1), 1, 1, 1, false)).get("id").toString();
    Map<String, Object> body = new HashMap<>(cancelBody(old));
    body.put("quoteId", quote);
    body.put("riskAccepted", true);
    long modification = Db.value(bookings.beginModification(guest, old, key(), body), "id");
    create(staff, today.plusDays(1), 1, 2);
    assertThrows(
        BusinessException.class,
        () -> bookings.finishModification(guest, modification, key(), Map.of("accepted", true)));
    assertEquals("CANCELLED", order(old).get("status"));
    assertEquals(
        "CANCELLED_OLD",
        db.one("SELECT state FROM modification WHERE id=?", modification).get("state"));
  }

  @Test
  void overnightDateBoundariesCrossYearWithoutChargingDepartureNight() {
    time("2026-12-31T02:00:00Z");
    LocalDate start = LocalDate.of(2026, 12, 31);
    db.jdbc.update(
        "INSERT INTO inventory_day(room_type_id,stay_date,capacity,public_price,online_price)"
            + " VALUES(?,?,2,10000,9000),(?,?,2,20000,18000)",
        type,
        start,
        type,
        start.plusDays(1));
    long id = create(staff, start, 2, 1);
    assertEquals(30000, Db.value(order(id), "amount"));
    assertEquals(
        2,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM booking_night WHERE booking_id=?", Long.class, id));
  }

  @Test
  void duplicateRoomNumbersAreScopedToHotelAndHistoricalReferencesCannotBeDeleted() {
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            db.jdbc.update(
                "INSERT INTO physical_room(hotel_id,room_type_id,number,state)"
                    + " VALUES(?,?,'201','READY')",
                hotel,
                type));
    create(guest, today, 1, 1);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> db.jdbc.update("DELETE FROM room_type WHERE id=?", type));
  }

  @Test
  void csrfAnonymousAndRoleBoundariesAreEnforcedByHttp() throws Exception {
    mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/manage/logs").param("hotelId", String.valueOf(hotel)).with(user(guest)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/manage/catalog")
                .with(user(outsider))
                .param("hotelId", String.valueOf(hotel))
                .param("start", today.toString())
                .param("end", today.plusDays(1).toString()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/catalog/search")
                .param("start", "bad")
                .param("end", today.toString())
                .param("guests", "1.5"))
        .andExpect(status().isBadRequest());
  }
}
