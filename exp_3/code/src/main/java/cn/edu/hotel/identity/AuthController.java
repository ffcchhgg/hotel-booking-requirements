package cn.edu.hotel.identity;

import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.Input;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final Db db;
  private final PasswordEncoder encoder;
  private final AuthenticationManager manager;
  private final SecurityContextRepository contexts;
  private final BusinessClock clock;

  public AuthController(
      Db db,
      PasswordEncoder encoder,
      AuthenticationManager manager,
      SecurityContextRepository contexts,
      BusinessClock clock) {
    this.db = db;
    this.encoder = encoder;
    this.manager = manager;
    this.contexts = contexts;
    this.clock = clock;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof Actor actor)) {
      return Map.of("authenticated", false, "today", clock.today().toString());
    }
    return Map.of(
        "authenticated", true, "user", actor.publicInfo(), "today", clock.today().toString());
  }

  @PostMapping("/login")
  public Map<String, Object> login(
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String username = Input.text(body, "username", 40);
    String password = Input.text(body, "password", 72);
    Authentication result =
        manager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    request.getSession(true);
    request.changeSessionId();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(result);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return Map.of("user", ((Actor) result.getPrincipal()).publicInfo());
  }

  @PostMapping("/register")
  public Map<String, Object> register(@RequestBody Map<String, Object> body) {
    Input.require(!body.containsKey("role") && !body.containsKey("hotelId"), "注册不能指定角色或酒店");
    String username = Input.text(body, "username", 40);
    Input.require(username.matches("[a-zA-Z][a-zA-Z0-9_]{3,39}"), "用户名须为4—40位字母、数字或下划线，以字母开头");
    String password = Input.text(body, "password", 72);
    Input.require(
        password.length() >= 10
            && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        "密码至少10个字符，UTF-8长度不超过72字节");
    String name = Input.text(body, "displayName", 60);
    try {
      long id =
          db.insert(
              "INSERT INTO app_user(username,password_hash,display_name,role)"
                  + " VALUES(?,?,?,'CUSTOMER')",
              username,
              encoder.encode(password),
              name);
      return Map.of("id", id, "message", "注册成功，请登录");
    } catch (DuplicateKeyException exception) {
      throw BusinessException.conflict("用户名已被使用");
    }
  }
}
