package dev.creatorstore.controller;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {
  private final AuthenticationService authentication;
  private final SessionService sessions;

  public AuthenticationController(AuthenticationService authentication, SessionService sessions) {
    this.authentication = authentication;
    this.sessions = sessions;
  }

  @RequestMapping(value = "/api/v1/authentication/check-unique-taken", method = RequestMethod.OPTIONS)
  public ResponseEntity<Void> uniquenessOptions() {
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/authentication/check-unique-taken")
  public Map<String, Object> uniqueness(@RequestBody Map<String, Object> body) {
    return authentication.uniqueness(body);
  }

  @PostMapping("/api/auth/register")
  public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request,
      HttpServletRequest httpRequest) {
    AuthenticationService.Registration registration = authentication.register(request);
    return ResponseEntity.status(201)
        .header(HttpHeaders.SET_COOKIE,
            sessions.cookie(registration.session(), httpRequest.isSecure()).toString())
        .body(registration.account());
  }
}
