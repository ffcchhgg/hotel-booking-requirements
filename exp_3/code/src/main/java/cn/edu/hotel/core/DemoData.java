package cn.edu.hotel.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hotel.demo", havingValue = "true")
public class DemoData implements ApplicationRunner {
  private final Db db;
  private final PasswordEncoder encoder;
  private final BusinessClock clock;

  public DemoData(Db db, PasswordEncoder encoder, BusinessClock clock) {
    this.db = db;
    this.encoder = encoder;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    db.transaction.executeWithoutResult(
        status -> {
          db.jdbc.execute("SELECT pg_advisory_xact_lock(241220103)");
          if (db.jdbc.queryForObject("SELECT COUNT(*) FROM hotel", Long.class) > 0) {
            return;
          }
          String[] names = {"梧桐院 · 校园客舍", "青禾馆 · 花园酒店", "书庭居 · 校园公寓"};
          String[] campuses = {"仙林校区", "鼓楼校区", "仙林校区"};
          for (int index = 0; index < names.length; index++) {
            long hotel =
                db.insert(
                    "INSERT INTO hotel(name,campus,description,image) VALUES(?,?,?,?)",
                    names[index],
                    campuses[index],
                    "步行可达校园，安静舒适的课程示意酒店。所有信息与照片仅供演示。",
                    "/assets/" + (index == 1 ? "garden" : "room") + ".jpg");
            String[] types = {"舒适大床房", "雅致双床房", "家庭套房"};
            for (int kind = 0; kind < types.length; kind++) {
              long type =
                  db.insert(
                      "INSERT INTO room_type(hotel_id,name,capacity,description,image)"
                          + " VALUES(?,?,?,?,?)",
                      hotel,
                      types[kind],
                      kind == 2 ? 3 : 2,
                      "独立卫浴、无线网络、书桌与舒适寝具。",
                      "/assets/" + (kind == 2 ? "suite" : "room") + ".jpg");
              int quantity = kind == 2 ? 4 : 6;
              long publicPrice = (500 + kind * 90 + index * 50) * 100L;
              long onlinePrice = (450 + kind * 90 + index * 50) * 100L;
              for (int room = 1; room <= quantity; room++) {
                db.jdbc.update(
                    "INSERT INTO physical_room(hotel_id,room_type_id,number,state)"
                        + " VALUES(?,?,?,'READY')",
                    hotel,
                    type,
                    (kind + 2) + String.format("%02d", room));
              }
              for (int day = -2; day <= 365; day++) {
                LocalDate date = clock.today().plusDays(day);
                db.jdbc.update(
                    "INSERT INTO"
                        + " inventory_day(room_type_id,stay_date,capacity,public_price,online_price)"
                        + " VALUES(?,?,?,?,?)",
                    type,
                    date,
                    quantity,
                    publicPrice,
                    onlinePrice);
              }
            }
          }
          String hash = encoder.encode("DemoHotel2026!");
          createUser("guest", "体验住客", "CUSTOMER", null, hash);
          createUser("guest2", "另一位住客", "CUSTOMER", null, hash);
          List<Map<String, Object>> hotels = db.rows("SELECT id FROM hotel ORDER BY id");
          for (int index = 0; index < hotels.size(); index++) {
            createUser(
                "staff" + (index + 1),
                "酒店前台" + (index + 1),
                "STAFF",
                Db.value(hotels.get(index), "id"),
                hash);
          }
          long admin = createUser("admin", "平台管理员", "ADMIN", null, hash);
          for (Map<String, Object> hotel : hotels) {
            db.jdbc.update(
                "INSERT INTO fee_policy(hotel_id,hourly_cents,created_by,created_at)"
                    + " VALUES(?,5000,?,?)",
                Db.value(hotel, "id"),
                admin,
                Db.time(clock.now()));
          }
        });
  }

  private long createUser(String username, String name, String role, Long hotel, String hash) {
    return db.insert(
        "INSERT INTO app_user(username,password_hash,display_name,role,hotel_id) VALUES(?,?,?,?,?)",
        username,
        hash,
        name,
        role,
        hotel);
  }
}
