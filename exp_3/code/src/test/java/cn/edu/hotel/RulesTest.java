package cn.edu.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.reception.ReceptionService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulesTest {
  @Test
  void feeUsesWholeHourCeiling() {
    assertEquals(0, ReceptionService.lateFee(-1, 5000));
    assertEquals(0, ReceptionService.lateFee(0, 5000));
    assertEquals(5000, ReceptionService.lateFee(1, 5000));
    assertEquals(5000, ReceptionService.lateFee(3600, 5000));
    assertEquals(10000, ReceptionService.lateFee(3601, 5000));
  }

  @Test
  void integerInputsRejectDecimalsExponentsAndOutOfBoundsValues() {
    for (Object value : new Object[] {"1.5", "1e2", 0, 31, "NaN", "", true}) {
      assertThrows(
          BusinessException.class, () -> Input.number(Map.of("guests", value), "guests", 1, 30));
    }
    assertEquals(7, Input.number(Map.of("guests", 7), "guests", 1, 30));
    assertEquals(30, Input.number(Map.of("guests", "30"), "guests", 1, 30));
  }
}
