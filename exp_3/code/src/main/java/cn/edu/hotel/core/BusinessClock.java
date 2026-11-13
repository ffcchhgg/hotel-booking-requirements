package cn.edu.hotel.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class BusinessClock {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  public Instant now() {
    return Instant.now();
  }

  public LocalDate today() {
    return now().atZone(ZONE).toLocalDate();
  }

  public Instant at(LocalDate date, int hour) {
    return date.atTime(hour, 0).atZone(ZONE).toInstant();
  }
}
