package cn.edu.hotel.core;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Db {
  public final JdbcTemplate jdbc;
  public final TransactionTemplate transaction;

  public Db(JdbcTemplate jdbc, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.transaction = transaction;
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, index) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
            Object value = rs.getObject(col);
            if (value instanceof Date date) {
              value = date.toLocalDate().toString();
            }
            if (value instanceof Timestamp timestamp) {
              value = timestamp.toInstant().toString();
            }
            row.put(rs.getMetaData().getColumnLabel(col), value);
          }
          return row;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = rows(sql, args);
    if (rows.isEmpty()) {
      throw BusinessException.missing();
    }
    return rows.getFirst();
  }

  public long insert(String sql, Object... args) {
    return jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
  }

  public void lockHotel(long id) {
    one("SELECT id FROM hotel WHERE id=? FOR UPDATE", id);
  }

  public static long value(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  public static String string(Map<String, Object> row, String key) {
    return String.valueOf(row.get(key));
  }

  public static LocalDate date(Map<String, Object> row, String key) {
    return LocalDate.parse(string(row, key));
  }

  public static Instant instant(Map<String, Object> row, String key) {
    return Instant.parse(string(row, key));
  }

  public static Timestamp time(Instant instant) {
    return Timestamp.from(instant);
  }
}
