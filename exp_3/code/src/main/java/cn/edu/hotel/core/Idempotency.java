package cn.edu.hotel.core;

import cn.edu.hotel.identity.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class Idempotency {
  private final Db db;
  private final Json json;

  public Idempotency(Db db, Json json) {
    this.db = db;
    this.json = json;
  }

  public Map<String, Object> execute(
      Actor actor,
      String action,
      String key,
      Object payload,
      Supplier<Map<String, Object>> operation) {
    Input.require(key != null && key.matches("[a-zA-Z0-9_-]{8,80}"), "请提供有效的请求标识");
    String digest = hash(json.canonical(payload));
    return db.transaction.execute(
        status -> {
          int inserted =
              db.jdbc.update(
                  "INSERT INTO request_record(actor_id,action,request_key,digest) VALUES(?,?,?,?)"
                      + " ON CONFLICT DO NOTHING",
                  actor.id(),
                  action,
                  key,
                  digest);
          if (inserted == 0) {
            Map<String, Object> previous =
                db.one(
                    "SELECT digest,response FROM request_record WHERE actor_id=? AND action=? AND"
                        + " request_key=?",
                    actor.id(),
                    action,
                    key);
            if (!digest.equals(previous.get("digest"))) {
              throw BusinessException.conflict("相同请求标识不能用于不同内容");
            }
            if (previous.get("response") == null) {
              throw BusinessException.conflict("请求处理中，请稍后重试");
            }
            return json.object(previous.get("response"));
          }
          Map<String, Object> result = operation.get();
          db.jdbc.update(
              "UPDATE request_record SET response=?::jsonb WHERE actor_id=? AND action=? AND"
                  + " request_key=?",
              json.write(result),
              actor.id(),
              action,
              key);
          return result;
        });
  }

  public String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
