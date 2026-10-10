package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import dev.creatorstore.service.IdentityAuthenticationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
  private final IdentityAuthenticationService authentication;
  private final SessionService sessions;

  AuthController(IdentityAuthenticationService authentication, SessionService sessions) {
    this.authentication = authentication;
    this.sessions = sessions;
  }

  @PostMapping("/api/auth/login")
  ResponseEntity<?> login(@RequestBody LoginIn body, HttpServletRequest request) {
    IdentityAuthenticationService.LoginResult result = authentication.login(
        body.handleOrEmail(), body.password(), ClientContext.from(request));
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
            sessions.cookie(result.session(), request.isSecure()).toString())
        .body(result.account());
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
    return ResponseEntity.ok(authentication.me(SessionCookies.readToken(request)));
  }

  record LoginIn(String handleOrEmail, String password) {}
}
