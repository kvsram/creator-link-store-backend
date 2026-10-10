package dev.creatorstore.controller;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
  public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request,
      HttpServletRequest httpRequest) {
    AuthenticationService.Registration registration = authentication.register(request,
        ClientContext.from(httpRequest));
    // The compatibility route follows the same verification-first invariant as the v1 route.
    // A registration never creates an authenticated browser session until the email challenge
    // is consumed successfully.
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(registration.account());
  }
}
