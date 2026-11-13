package cn.edu.hotel.core;

import cn.edu.hotel.identity.Actor;
import org.springframework.stereotype.Component;

@Component
public class Audit {
  private final Db db;
  private final BusinessClock clock;

  public Audit(Db db, BusinessClock clock) {
    this.db = db;
    this.clock = clock;
  }

  // Only explicit summaries enter this table; never serialize a request or guest document.
  public void record(Actor actor, long hotel, String action, String type, long id, String summary) {
    db.jdbc.update(
        "INSERT INTO"
            + " operation_log(actor_id,hotel_id,action,object_type,object_id,summary,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        actor == null ? null : actor.id(),
        hotel,
        action,
        type,
        id,
        summary,
        Db.time(clock.now()));
  }
}
