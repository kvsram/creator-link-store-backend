package dev.creatorstore.service;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.LoginThrottleService;
import dev.creatorstore.identity.PasswordService;
import dev.creatorstore.identity.SecurityFingerprintService;
import dev.creatorstore.identity.SecurityEventService;
import dev.creatorstore.identity.SessionCookies;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.identity.TestAuthDeliveryService;
import dev.creatorstore.support.ApiErrorException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Application layer for password authentication, recovery, and email verification. */
@Service
public class IdentityAuthenticationService {
  private static final Duration VERIFICATION_TTL = Duration.ofHours(24);
  private static final Duration RESET_TTL = Duration.ofMinutes(30);
  private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
  private static final Duration ACCOUNT_LOCK = Duration.ofMinutes(15);
  private static final int ACCOUNT_FAILURE_THRESHOLD = 5;
  private static final int GENERATED_HANDLE_ATTEMPTS = 5;
  private static final String INVALID_CREDENTIALS = "Invalid handle/email or password.";

  private final RegistrationTransactionService registrations;
  private final IdentityRepository identities;
  private final IdentityChallengeTransactionService challenges;
  private final IdentityLoginTransactionService loginTransactions;
  private final PasswordService passwords;
  private final SessionService sessions;
  private final LoginThrottleService throttle;
  private final SecurityFingerprintService fingerprints;
  private final SecurityEventService securityEvents;
  private final TestAuthDeliveryService testDeliveries;

  public IdentityAuthenticationService(RegistrationTransactionService registrations,
                                       IdentityRepository identities,
                                       IdentityChallengeTransactionService challenges,
                                       IdentityLoginTransactionService loginTransactions,
                                       PasswordService passwords, SessionService sessions,
                                       LoginThrottleService throttle,
                                       SecurityFingerprintService fingerprints,
                                       SecurityEventService securityEvents,
                                       TestAuthDeliveryService testDeliveries) {
    this.registrations = registrations;
    this.identities = identities;
    this.challenges = challenges;
    this.loginTransactions = loginTransactions;
    this.passwords = passwords;
    this.sessions = sessions;
    this.throttle = throttle;
    this.fingerprints = fingerprints;
    this.securityEvents = securityEvents;
    this.testDeliveries = testDeliveries;
  }

  public Registration register(RegisterRequest request, ClientContext context,
                               boolean verificationRequired) {
    ValidatedRegistration valid = validate(request);
    if (!throttle.allowRegistration(context.address())) {
      event(null, "REGISTRATION_RATE_LIMITED", "blocked", context, valid.email());
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many registration attempts. Try again later.");
    }
    // Expensive password work deliberately occurs before the transactional database writer.
    String verifier = passwords.encode(valid.password());
    String realLegacyVerifier = passwords.legacyVerifier(valid.password());
    String creatorLegacyVerifier = verificationRequired
        ? passwords.disabledLegacyVerifier() : realLegacyVerifier;
    String transitionalLegacyVerifier = verificationRequired ? realLegacyVerifier : null;
    String rawVerificationToken = SessionCookies.newToken();
    OffsetDateTime verificationExpiry = now().plus(VERIFICATION_TTL);
    String status = verificationRequired ? "pending_verification" : "active";
    RegistrationTransactionService.CreatedAccount created = createAccount(
        valid, creatorLegacyVerifier, verifier, transitionalLegacyVerifier, status,
        verificationRequired, rawVerificationToken, verificationExpiry, context);

    Optional<UUID> deliveryId = testDeliveries.deliver("email_verification", valid.email(),
        rawVerificationToken, verificationExpiry);
    event(created.id(), "ACCOUNT_REGISTERED", "success", context, valid.email());

    SessionService.IssuedSession session = created.session();
    Map<String, Object> account = accountResponse(created.id());
    account.put("plan", "free");
    account.put("onboarding_next", verificationRequired ? "/verify-email" : "/dashboard/");
    account.put("authenticated", !verificationRequired);
    account.put("verificationRequired", verificationRequired);
    deliveryId.ifPresent(value -> account.put("testDeliveryId", value));
    return new Registration(account, session);
  }

  public LoginResult login(String suppliedIdentifier, String rawPassword, ClientContext context) {
    String identifier = suppliedIdentifier == null ? ""
        : suppliedIdentifier.trim().toLowerCase();
    String password = rawPassword == null ? "" : rawPassword;
    if (identifier.isBlank() || password.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Handle/email and password are required.");
    }
    if (!throttle.allowLogin(context.address())) {
      event(null, "LOGIN_RATE_LIMITED", "blocked", context, identifier);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many login attempts. Try again later.");
    }

    Optional<IdentityRepository.LoginAccount> found = identities.findForLogin(identifier);
    if (found.isEmpty()) {
      passwords.consumeComparableWork(password);
      event(null, "LOGIN_FAILED", "invalid_credentials", context, identifier);
      throw unauthorized();
    }

    IdentityRepository.LoginAccount account = found.get();
    boolean passwordMatches = passwords.matches(password, account.verifier());
    if (!passwordMatches && passwords.isLegacyBcrypt(account.verifier())) {
      passwords.consumeComparableWork(password);
    }
    OffsetDateTime current = now();
    boolean locked = account.lockedUntil() != null && account.lockedUntil().isAfter(current);
    if (!passwordMatches || locked || "suspended".equals(account.status())
        || "deleted".equals(account.status())) {
      if (!passwordMatches && !locked) {
        identities.recordFailure(account.id(), current, ACCOUNT_FAILURE_THRESHOLD,
            current.minus(FAILURE_WINDOW), current.plus(ACCOUNT_LOCK));
      }
      event(account.id(), "LOGIN_FAILED", "invalid_credentials", context, identifier);
      throw unauthorized();
    }
    if ("pending_verification".equals(account.status())) {
      event(account.id(), "LOGIN_FAILED", "email_not_verified", context, identifier);
      throw new ApiErrorException(HttpStatus.FORBIDDEN,
          "Verify your email before signing in.", "EMAIL_NOT_VERIFIED",
          Map.of("emailHint", maskEmail(account.email())));
    }

    String upgradeCandidate = passwords.needsUpgrade(account.verifier())
        ? passwords.encode(password) : null;
    IdentityLoginTransactionService.Result finalized = loginTransactions.finalizeLogin(
        account.id(), account.verifier(), password, upgradeCandidate, context);
    if (finalized.outcome() == IdentityLoginTransactionService.Outcome.INVALID) {
      event(account.id(), "LOGIN_FAILED", "invalid_credentials", context, identifier);
      throw unauthorized();
    }
    if (finalized.outcome() == IdentityLoginTransactionService.Outcome.EMAIL_NOT_VERIFIED) {
      event(account.id(), "LOGIN_FAILED", "email_not_verified", context, identifier);
      throw new ApiErrorException(HttpStatus.FORBIDDEN,
          "Verify your email before signing in.", "EMAIL_NOT_VERIFIED",
          Map.of("emailHint", maskEmail(account.email())));
    }
    event(account.id(), "LOGIN_SUCCEEDED", "success", context, identifier);
    return new LoginResult(accountResponse(account.id()), finalized.session());
  }

  public LoginResult verifyEmail(String rawToken, ClientContext context) {
    String token = rawToken == null ? "" : rawToken.trim();
    String tokenHash = SessionCookies.hash(token);
    if (!throttle.allowChallenge(context.address(), "email-verification:" + tokenHash)) {
      event(null, "EMAIL_VERIFICATION_RATE_LIMITED", "blocked", context, null);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many requests. Try again later.");
    }
    var verified = challenges.verifyEmail(tokenHash, context);
    event(verified.accountId(), "EMAIL_VERIFIED", "success", context, null);
    return new LoginResult(accountResponse(verified.accountId()), verified.session());
  }

  public Map<String, Object> resendEmailVerification(String suppliedEmail,
                                                     ClientContext context) {
    String email = CreatorIdentityPolicy.normalizeEmail(suppliedEmail);
    if (!throttle.allowChallenge(context.address(), email)) {
      event(null, "EMAIL_VERIFICATION_RATE_LIMITED", "blocked", context, email);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many requests. Try again later.");
    }
    String rawToken = SessionCookies.newToken();
    OffsetDateTime expiry = now().plus(VERIFICATION_TTL);
    Optional<Long> accountId = challenges.createEmailVerification(email,
        SessionCookies.hash(rawToken), expiry);
    accountId.ifPresent(value -> event(value, "EMAIL_VERIFICATION_REQUESTED", "accepted",
        context, email));
    Optional<UUID> deliveryId = testDeliveries.deliver("email_verification", email, rawToken,
        expiry);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", true);
    deliveryId.ifPresent(value -> response.put("testDeliveryId", value));
    return response;
  }

  public Map<String, Object> requestPasswordReset(String suppliedEmail, ClientContext context) {
    String email = CreatorIdentityPolicy.normalizeEmail(suppliedEmail);
    if (!throttle.allowChallenge(context.address(), email)) {
      event(null, "PASSWORD_RESET_RATE_LIMITED", "blocked", context, email);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many requests. Try again later.");
    }
    String rawToken = SessionCookies.newToken();
    OffsetDateTime expiry = now().plus(RESET_TTL);
    Optional<Long> accountId = challenges.createPasswordReset(email,
        SessionCookies.hash(rawToken), expiry);
    accountId.ifPresent(value -> event(value, "PASSWORD_RESET_REQUESTED", "accepted", context,
        email));
    Optional<UUID> deliveryId = testDeliveries.deliver("password_reset", email, rawToken, expiry);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", true);
    deliveryId.ifPresent(value -> response.put("testDeliveryId", value));
    return response;
  }

  public void resetPassword(String rawToken, String newPassword, ClientContext context) {
    validatePassword(newPassword);
    String token = rawToken == null ? "" : rawToken.trim();
    String tokenHash = SessionCookies.hash(token);
    if (!throttle.allowChallenge(context.address(), "password-reset:" + tokenHash)) {
      event(null, "PASSWORD_RESET_RATE_LIMITED", "blocked", context, null);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many requests. Try again later.");
    }
    if (!challenges.hasEligiblePasswordReset(tokenHash)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Password-reset token is invalid or expired.");
    }
    long accountId = challenges.resetPassword(tokenHash,
        passwords.encode(newPassword), passwords.legacyVerifier(newPassword));
    event(accountId, "PASSWORD_CHANGED", "success", context, null);
  }

  public Map<String, Object> me(String token) {
    SessionService.AuthenticatedSession session = sessions.resolve(token).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated."));
    return accountResponse(session.accountId());
  }

  public Map<String, Object> accountResponse(long accountId) {
    IdentityRepository.AccountView account = identities.findAccount(accountId).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator account not found."));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", account.id());
    response.put("publicId", account.publicId());
    response.put("handle", account.handle());
    response.put("displayName", account.displayName());
    response.put("email", account.email());
    response.put("bio", account.bio());
    response.put("phone", account.phone());
    response.put("avatarUrl", account.avatarUrl());
    response.put("status", account.status());
    response.put("emailVerified", account.emailVerifiedAt() != null);
    return response;
  }

  private void event(Long accountId, String type, String outcome, ClientContext context,
                     String identifier) {
    try {
      securityEvents.record(accountId, type, outcome, context.requestId(),
          fingerprints.fingerprint(identifier), fingerprints.fingerprint(context.address()),
          fingerprints.fingerprint(context.userAgent()));
    } catch (DataAccessException ignored) {
      // Authentication must not fail after a session/token was committed solely because the
      // append-only audit sink is temporarily unavailable. Database monitoring still catches it.
    }
  }

  private static ValidatedRegistration validate(RegisterRequest request) {
    if (request == null) throw invalidRegistration();
    String displayName = CreatorIdentityPolicy.trim(request.displayName());
    String email = CreatorIdentityPolicy.normalizeEmail(request.email());
    String phone = CreatorIdentityPolicy.nullablePhone(request.phone());
    if (displayName.isBlank() || displayName.length() > 80
        || !CreatorIdentityPolicy.isValidEmail(email)
        || !CreatorIdentityPolicy.isValidPhone(phone)) {
      throw invalidRegistration();
    }
    validatePassword(request.password());
    String requestedHandle = CreatorIdentityPolicy.normalizeHandle(request.handle());
    boolean generateHandle = requestedHandle.isBlank();
    if (!generateHandle && !CreatorIdentityPolicy.isValidHandle(requestedHandle)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Store link must use 3-40 letters, numbers, or underscores.");
    }
    if (!generateHandle && CreatorIdentityPolicy.isReservedHandle(requestedHandle)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "That store link is reserved for an application page. Choose another one.");
    }
    String handle = generateHandle ? CreatorIdentityPolicy.handleBase(displayName) : requestedHandle;
    return new ValidatedRegistration(handle, displayName, email, phone, request.password(),
        generateHandle);
  }

  public static void validatePassword(String password) {
    if (password == null || password.isBlank()
        || password.codePointCount(0, password.length()) < 12
        || password.codePointCount(0, password.length()) > 128) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Password must contain 12-128 characters.");
    }
  }

  private static ResponseStatusException invalidRegistration() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Enter a creator or business name, a valid email, and an optional valid phone number.");
  }

  private RegistrationTransactionService.CreatedAccount createAccount(
      ValidatedRegistration valid, String creatorLegacyVerifier, String verifier,
      String transitionalLegacyVerifier, String status, boolean verificationRequired,
      String rawVerificationToken, OffsetDateTime verificationExpiry, ClientContext context) {
    String handle = valid.handle();
    if (valid.generateHandle() && CreatorIdentityPolicy.isReservedHandle(handle)) {
      handle = randomHandle(handle);
    }
    for (int attempt = 0; attempt < GENERATED_HANDLE_ATTEMPTS; attempt++) {
      try {
        return registrations.create(handle, valid.displayName(), valid.email(), valid.phone(),
            creatorLegacyVerifier, verifier, transitionalLegacyVerifier, status,
            !verificationRequired, SessionCookies.hash(rawVerificationToken), verificationExpiry,
            !verificationRequired, context);
      } catch (ResponseStatusException conflict) {
        boolean generatedHandleCollision = valid.generateHandle()
            && conflict.getStatusCode().value() == HttpStatus.CONFLICT.value()
            && registrations.isHandleClaimed(handle);
        if (!generatedHandleCollision) throw conflict;
        handle = randomHandle(valid.handle());
      }
    }
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "We could not prepare a unique store link. Try again.");
  }

  private static String randomHandle(String base) {
    return CreatorIdentityPolicy.suffixedHandle(base, UUID.randomUUID().toString());
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String maskEmail(String email) {
    int separator = email == null ? -1 : email.indexOf('@');
    if (separator <= 0) return "***";
    String local = email.substring(0, separator);
    return local.substring(0, 1) + "***" + email.substring(separator);
  }

  private record ValidatedRegistration(String handle, String displayName, String email,
                                       String phone, String password, boolean generateHandle) {}

  public record Registration(Map<String, Object> account,
                             SessionService.IssuedSession session) {}

  public record LoginResult(Map<String, Object> account,
                            SessionService.IssuedSession session) {}
}
