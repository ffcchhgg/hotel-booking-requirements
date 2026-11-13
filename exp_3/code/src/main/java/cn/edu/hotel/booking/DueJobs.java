package cn.edu.hotel.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hotel.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class DueJobs {
  private static final Logger LOG = LoggerFactory.getLogger(DueJobs.class);
  private final BookingService bookings;

  public DueJobs(BookingService bookings) {
    this.bookings = bookings;
  }

  @Scheduled(fixedDelayString = "${hotel.scan-delay:5000}", initialDelay = 10000)
  public void scan() {
    try {
      bookings.processDue();
    } catch (Exception exception) {
      LOG.error(
          "Due-order scan failed; next scan will retry ({})", exception.getClass().getSimpleName());
    }
  }
}
