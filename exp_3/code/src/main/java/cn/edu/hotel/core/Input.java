package cn.edu.hotel.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

public final class Input {
  private Input() {}

  public static String text(Map<String, ?> body, String name, int max) {
    Object value = body.get(name);
    if (!(value instanceof String)) {
      throw BusinessException.invalid(name + " 不能为空");
    }
    String result = ((String) value).trim();
    if (result.isBlank() || result.length() > max || result.indexOf('\0') >= 0) {
      throw BusinessException.invalid(name + " 长度或内容不合法");
    }
    return result;
  }

  public static String optional(Map<String, ?> body, String name, int max) {
    if (body.get(name) == null || "".equals(body.get(name))) {
      return "";
    }
    return text(body, name, max);
  }

  public static long number(Map<String, ?> body, String name, long min, long max) {
    Object value = body.get(name);
    if (value == null || !value.toString().matches("[0-9]{1,18}")) {
      throw BusinessException.invalid(name + " 必须填写整数");
    }
    try {
      long result = Long.parseLong(value.toString());
      if (result < min || result > max) {
        throw BusinessException.invalid(name + " 超出允许范围 " + min + "—" + max);
      }
      return result;
    } catch (NumberFormatException exception) {
      throw BusinessException.invalid(name + " 超出允许范围");
    }
  }

  public static long id(Map<String, ?> body, String name) {
    return number(body, name, 1, Long.MAX_VALUE);
  }

  public static boolean flag(Map<String, ?> body, String name) {
    Object value = body.get(name);
    if (value == null) {
      return false;
    }
    if (!(value instanceof Boolean)) {
      throw BusinessException.invalid(name + " 必须是布尔值");
    }
    return Boolean.TRUE.equals(value);
  }

  public static LocalDate date(Map<String, ?> body, String name) {
    try {
      String value = text(body, name, 10);
      LocalDate result = LocalDate.parse(value);
      if (!result.toString().equals(value)) {
        throw BusinessException.invalid("日期格式必须为 yyyy-MM-dd");
      }
      return result;
    } catch (DateTimeParseException exception) {
      throw BusinessException.invalid(name + " 日期无效");
    }
  }

  public static void require(boolean condition, String message) {
    if (!condition) {
      throw BusinessException.invalid(message);
    }
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> objects(Map<String, ?> body, String name) {
    Object value = body.get(name);
    if (!(value instanceof List<?> list)
        || list.isEmpty()
        || list.size() > 30
        || list.stream().anyMatch(item -> !(item instanceof Map))) {
      throw BusinessException.invalid(name + " 必须包含1—30项");
    }
    return (List<Map<String, Object>>) value;
  }
}
