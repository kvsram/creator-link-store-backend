package dev.creatorstore.controller;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.service.AuthenticationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {
  private final AuthenticationService authentication;

  public AuthenticationController(AuthenticationService authentication) {
    this.authentication = authentication;
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
  public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
    return ResponseEntity.status(201).body(authentication.register(request));
  }
}
