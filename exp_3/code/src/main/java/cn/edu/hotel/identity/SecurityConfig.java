package cn.edu.hotel.identity;

import cn.edu.hotel.core.Db;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
public class SecurityConfig {
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public UserDetailsService users(Db db) {
    return username -> {
      List<Map<String, Object>> found =
          db.rows("SELECT * FROM app_user WHERE username=?", username);
      if (found.isEmpty()) {
        throw new UsernameNotFoundException("Invalid credentials");
      }
      return Actor.from(found.getFirst());
    };
  }

  @Bean
  public AuthenticationManager authenticationManager(
      UserDetailsService users, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  @Bean
  public SecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  public SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts)
      throws Exception {
    http.securityContext(context -> context.securityContextRepository(contexts));
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(
                    "/",
                    "/index.html",
                    "/style.css",
                    "/app.js",
                    "/assets/**",
                    "/favicon.ico",
                    "/error",
                    "/api/auth/csrf",
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/me",
                    "/api/catalog/**",
                    "/api/images/**",
                    "/api/health")
                .permitAll()
                .anyRequest()
                .authenticated());
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, exception) -> {
                      response.setStatus(401);
                      response.setContentType("application/json;charset=UTF-8");
                      response
                          .getWriter()
                          .write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                    })
                .accessDeniedHandler(
                    (request, response, exception) -> {
                      response.setStatus(403);
                      response.setContentType("application/json;charset=UTF-8");
                      response
                          .getWriter()
                          .write("{\"code\":\"FORBIDDEN\",\"message\":\"请求无权限或安全令牌已过期，请刷新后重试\"}");
                    }));
    http.logout(
        logout ->
            logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler(
                    (request, response, authentication) -> {
                      response.setStatus(204);
                    }));
    http.headers(
        headers ->
            headers.contentSecurityPolicy(
                policy ->
                    policy.policyDirectives(
                        "default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src"
                            + " 'self'; object-src 'none'; base-uri 'self'; frame-ancestors"
                            + " 'none'")));
    return http.build();
  }
}
