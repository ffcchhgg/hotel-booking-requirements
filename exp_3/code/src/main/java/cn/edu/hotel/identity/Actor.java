package cn.edu.hotel.identity;

import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record Actor(
    long id,
    String username,
    String displayName,
    String password,
    String role,
    Long hotelId,
    boolean enabled)
    implements UserDetails, Serializable {
  public static Actor from(Map<String, Object> row) {
    return new Actor(
        Db.value(row, "id"),
        Db.string(row, "username"),
        Db.string(row, "display_name"),
        Db.string(row, "password_hash"),
        Db.string(row, "role"),
        row.get("hotel_id") == null ? null : Db.value(row, "hotel_id"),
        Boolean.TRUE.equals(row.get("enabled")));
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  public boolean is(String expected) {
    return role.equals(expected);
  }

  public void require(String expected) {
    if (!is(expected)) {
      throw new BusinessException(403, "FORBIDDEN", "当前账号没有此操作权限");
    }
  }

  public void staff(long hotel) {
    require("STAFF");
    if (hotelId == null || hotelId != hotel) {
      throw BusinessException.missing();
    }
  }

  public Map<String, Object> publicInfo() {
    return Map.of(
        "id",
        id,
        "username",
        username,
        "displayName",
        displayName,
        "role",
        role,
        "hotelId",
        hotelId == null ? 0 : hotelId);
  }
}
