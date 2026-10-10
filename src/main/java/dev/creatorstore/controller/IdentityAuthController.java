package dev.creatorstore.controller;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.SessionCookies;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.identity.TestAuthDeliveryService;
import dev.creatorstore.service.IdentityAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class IdentityAuthController {
  private final IdentityAuthenticationService authentication;
  private final SessionService sessions;
  private final TestAuthDeliveryService testDeliveries;

  public IdentityAuthController(IdentityAuthenticationService authentication,
                                SessionService sessions,
                                TestAuthDeliveryService testDeliveries) {
    this.authentication = authentication;
    this.sessions = sessions;
    this.testDeliveries = testDeliveries;
  }

  @PostMapping("/registrations")
  ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest body,
                                                HttpServletRequest request) {
    var registration = authentication.register(body, ClientContext.from(request), true);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(registration.account());
  }

  @PostMapping("/email-verifications")
  ResponseEntity<Map<String, Object>> verifyEmail(@RequestBody TokenRequest body,
                                                   HttpServletRequest request) {
    var result = authentication.verifyEmail(body.token(), ClientContext.from(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.cookie(result.session(), request.isSecure()).toString())
        .body(result.account());
  }

  @PostMapping("/email-verifications/resend")
  ResponseEntity<Map<String, Object>> resendVerification(@RequestBody EmailRequest body,
                                                          HttpServletRequest request) {
    return ResponseEntity.accepted().body(authentication.resendEmailVerification(body.email(),
        ClientContext.from(request)));
  }

  @PostMapping("/sessions")
  ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
                                             HttpServletRequest request) {
    var result = authentication.login(body.handleOrEmail(), body.password(),
        ClientContext.from(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.cookie(result.session(), request.isSecure()).toString())
        .body(result.account());
  }

  @GetMapping("/sessions")
  Map<String, Object> sessions(HttpServletRequest request) {
    long accountId = authenticatedAccountId(request);
    return Map.of("sessions", sessions.list(accountId, SessionCookies.readToken(request)));
  }

  @DeleteMapping("/sessions/current")
  ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
    sessions.revoke(SessionCookies.readToken(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie(request.isSecure()).toString())
        .body(Map.of("ok", true));
  }

  @DeleteMapping("/sessions/{publicId}")
  Map<String, Object> revoke(@PathVariable UUID publicId, HttpServletRequest request) {
    sessions.revoke(authenticatedAccountId(request), publicId);
    return Map.of("ok", true);
  }

  @PostMapping("/password-reset-requests")
  ResponseEntity<Map<String, Object>> requestPasswordReset(@RequestBody EmailRequest body,
                                                            HttpServletRequest request) {
    return ResponseEntity.accepted().body(authentication.requestPasswordReset(body.email(),
        ClientContext.from(request)));
  }

  @PostMapping("/password-resets")
  ResponseEntity<Map<String, Object>> resetPassword(@RequestBody PasswordResetRequest body,
                                                     HttpServletRequest request) {
    authentication.resetPassword(body.token(), body.newPassword(), ClientContext.from(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie(request.isSecure()).toString())
        .body(Map.of("ok", true));
  }

  @GetMapping("/test-deliveries/{deliveryId}")
  TestAuthDeliveryService.Delivery testDelivery(@PathVariable UUID deliveryId,
      @RequestHeader(value = "X-Test-Auth-Key", required = false) String key) {
    return testDeliveries.retrieve(deliveryId, key);
  }

  private static long authenticatedAccountId(HttpServletRequest request) {
    Object value = request.getAttribute("accountId");
    if (!(value instanceof Number number)) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
          "Not authenticated.");
    }
    return number.longValue();
  }

  record TokenRequest(String token) {}
  record EmailRequest(String email) {}
  record LoginRequest(String handleOrEmail, String password) {}
  record PasswordResetRequest(String token, String newPassword) {}
}
