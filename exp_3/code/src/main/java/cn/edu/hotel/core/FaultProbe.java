package cn.edu.hotel.core;

import org.springframework.stereotype.Component;

/** No-op in the application. Tests replace this bean to inject transaction failures. */
@Component
public class FaultProbe {
  public void checkpoint(String name) {
    // Deliberately no HTTP or configuration switch for fault injection.
  }
}
