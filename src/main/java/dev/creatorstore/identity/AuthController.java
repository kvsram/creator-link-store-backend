package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords;
  private final SessionService sessions;

  AuthController(JdbcTemplate db, BCryptPasswordEncoder passwords, SessionService sessions) {
    this.db = db;
    this.passwords = passwords;
    this.sessions = sessions;
  }

  @PostMapping("/api/auth/login")
  ResponseEntity<?> login(@RequestBody LoginIn body, HttpServletRequest request) {
    String identifier = body.handleOrEmail() == null ? "" : body.handleOrEmail().trim().toLowerCase();
    String password = body.password() == null ? "" : body.password();
    if (identifier.isBlank() || password.isBlank())
      return ResponseEntity.badRequest().body(Map.of("error", "Handle/email and password are required."));

    List<Map<String, Object>> rows = db.queryForList(
        "select id as \"id\",handle as \"handle\",display_name as \"display_name\","
            + "password_hash as \"password_hash\" from creators where handle=? or email=?",
        identifier, identifier);
    if (rows.isEmpty() || !passwords.matches(password, String.valueOf(rows.get(0).get("password_hash"))))
      return ResponseEntity.status(401).body(Map.of("error", "Invalid handle/email or password."));

    long creatorId = ((Number) rows.get(0).get("id")).longValue();
    SessionService.IssuedSession session = sessions.issue(creatorId);

    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
            sessions.cookie(session, request.isSecure()).toString())
        .body(Map.of("id", creatorId, "handle", rows.get(0).get("handle"), "displayName", rows.get(0).get("display_name")));
  }

  @PostMapping("/api/auth/logout")
  ResponseEntity<?> logout(HttpServletRequest request) {
    String token = SessionCookies.readToken(request);
    sessions.revoke(token);
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
            sessions.clearedCookie(request.isSecure()).toString())
        .body(Map.of("ok", true));
  }

  @GetMapping("/api/auth/me")
  ResponseEntity<?> me(HttpServletRequest request) {
    String token = SessionCookies.readToken(request);
    if (token == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
    List<Map<String, Object>> rows = db.queryForList(
        "select c.id as \"id\",c.handle as \"handle\",c.display_name as \"displayName\","
            + "c.email as \"email\" from sessions s join creators c on c.id=s.creator_id "
            + "where s.id=? and s.revoked_at is null and s.expires_at > current_timestamp",
        SessionCookies.hash(token));
    if (rows.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
    return ResponseEntity.ok(rows.get(0));
  }

  record LoginIn(String handleOrEmail, String password) {}
}
