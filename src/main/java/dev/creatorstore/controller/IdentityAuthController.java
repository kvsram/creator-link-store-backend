package dev.creatorstore.controller;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.CreatorPrincipal;
import dev.creatorstore.identity.SessionCookies;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.identity.TestAuthDeliveryService;
import dev.creatorstore.service.IdentityAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

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
  ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest body,
                                                HttpServletRequest request) {
    var registration = authentication.register(body, ClientContext.from(request), true);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(registration.account());
  }

  @PostMapping("/email-verifications")
  ResponseEntity<Map<String, Object>> verifyEmail(@Valid @RequestBody TokenRequest body,
                                                   HttpServletRequest request) {
    var result = authentication.verifyEmail(body.token(), ClientContext.from(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.cookie(result.session(), request.isSecure()).toString())
        .body(result.account());
  }

  @PostMapping("/email-verifications/resend")
  ResponseEntity<Map<String, Object>> resendVerification(@Valid @RequestBody EmailRequest body,
                                                          HttpServletRequest request) {
    return ResponseEntity.accepted().body(authentication.resendEmailVerification(body.email(),
        ClientContext.from(request)));
  }

  @PostMapping("/sessions")
  ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest body,
                                             HttpServletRequest request) {
    var result = authentication.login(body.handleOrEmail(), body.password(),
        ClientContext.from(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.cookie(result.session(), request.isSecure()).toString())
        .body(result.account());
  }

  @GetMapping("/sessions")
  Map<String, Object> sessions(@AuthenticationPrincipal CreatorPrincipal principal,
                               HttpServletRequest request) {
    return Map.of("sessions", sessions.list(principal.accountId(),
        SessionCookies.readToken(request)));
  }

  @GetMapping("/me")
  Map<String, Object> me(@AuthenticationPrincipal CreatorPrincipal principal) {
    return authentication.accountResponse(principal.accountId());
  }

  @GetMapping("/csrf")
  Map<String, Object> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(),
        "token", token.getToken());
  }

  @DeleteMapping("/sessions/current")
  ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
    sessions.revoke(SessionCookies.readToken(request));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessions.clearedCookie(request.isSecure()).toString())
        .body(Map.of("ok", true));
  }

  @DeleteMapping("/sessions/{publicId}")
  Map<String, Object> revoke(@PathVariable UUID publicId,
                             @AuthenticationPrincipal CreatorPrincipal principal) {
    sessions.revoke(principal.accountId(), publicId);
    return Map.of("ok", true);
  }

  @PostMapping("/password-reset-requests")
  ResponseEntity<Map<String, Object>> requestPasswordReset(@Valid @RequestBody EmailRequest body,
                                                            HttpServletRequest request) {
    return ResponseEntity.accepted().body(authentication.requestPasswordReset(body.email(),
        ClientContext.from(request)));
  }

  @PostMapping("/password-resets")
  ResponseEntity<Map<String, Object>> resetPassword(@Valid @RequestBody PasswordResetRequest body,
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

  record TokenRequest(@NotBlank @Size(max = 256) String token) {}
  record EmailRequest(@NotBlank @Email @Size(max = 320) String email) {}
  record LoginRequest(@NotBlank @Size(max = 320) String handleOrEmail,
                      @NotBlank @Size(max = 128) String password) {}
  record PasswordResetRequest(@NotBlank @Size(max = 256) String token,
                              @NotBlank @Size(min = 12, max = 128) String newPassword) {}
}
