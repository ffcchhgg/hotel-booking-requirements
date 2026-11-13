package cn.edu.hotel.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("JSON encoding failed", exception);
    }
  }

  public Map<String, Object> object(Object value) {
    try {
      return mapper.readValue(value.toString(), new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored JSON is invalid", exception);
    }
  }

  public String canonical(Object value) {
    return write(ordered(value));
  }

  private Object ordered(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new TreeMap<>();
      map.forEach((key, item) -> result.put(key.toString(), ordered(item)));
      return result;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(this::ordered).toList();
    }
    return value;
  }
}
