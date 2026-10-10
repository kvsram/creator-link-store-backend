package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.creatorstore.bootstrap.DemoDataInitializer;
import dev.creatorstore.identity.AuthDeliveryRepository;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.PasswordService;
import dev.creatorstore.identity.SessionCookies;
import dev.creatorstore.service.IdentityChallengeTransactionService;
import dev.creatorstore.service.IdentityLoginTransactionService;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:identity-v1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "instagram.autodm-worker-enabled=false",
    "app.auth.test-delivery-enabled=true",
    "app.auth.test-delivery-key=identity-e2e-secret",
    "app.auth.login-ip-attempts=100",
    "app.auth.registration-ip-attempts=100",
    "app.auth.challenge-ip-attempts=100",
    "app.auth.challenge-identifier-attempts=100"
})
@AutoConfigureMockMvc
@Sql("/auth-integration-schema.sql")
class IdentityAuthIntegrationTest {
  private static final String PASSWORD = "correct horse battery staple";
  private static final String NEW_PASSWORD = "new correct horse battery staple";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcTemplate database;
  @Autowired private IdentityRepository identities;
  @Autowired private AuthDeliveryRepository authDeliveries;
  @Autowired private IdentityChallengeTransactionService challenges;
  @Autowired private IdentityLoginTransactionService loginTransactions;
  @Autowired private PasswordService passwordService;
  @Autowired private BCryptPasswordEncoder bcrypt;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

  @Test
  void registrationGeneratesAStoreLinkWhenTheClientDoesNotAskForOne() throws Exception {
    MvcResult first = mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registrationWithoutHandle("Alex Creates", "auto-one@example.test",
                "twelve-chars")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.handle").value("alex_creates"))
        .andReturn();

    MvcResult second = mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registrationWithoutHandle("Alex Creates", "auto-two@example.test",
                "twelve-chars")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.handle").value(
            org.hamcrest.Matchers.matchesPattern("alex_creates_[a-z0-9]{12}")))
        .andReturn();

    String firstHandle = body(first).get("handle").asText();
    String secondHandle = body(second).get("handle").asText();
    assertThat(secondHandle).isNotEqualTo(firstHandle);
    assertThat(database.queryForObject("select count(*) from creator_handles where handle in (?,?) "
        + "and state='active'", Integer.class, firstHandle, secondHandle)).isEqualTo(2);
  }

  @Test
  void registrationKeepsExplicitLinksAndUsesSafeFallbacksForAutomaticOnes() throws Exception {
    mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registration("chosen_link", "chosen@example.test", "twelve-chars")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.handle").value("chosen_link"));

    mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registrationWithoutHandle("Login", "reserved-name@example.test",
                "twelve-chars")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.handle").value(
            org.hamcrest.Matchers.matchesPattern("login_[a-z0-9]{12}")));

    mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registrationWithoutHandle("సృష్టికర్త", "unicode-name@example.test",
                "twelve-chars")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.handle").value("creator"));
  }

  @Test
  void registrationRejectsElevenCharacterPasswordsAndAcceptsTwelve() throws Exception {
    mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
        .content(registrationWithoutHandle("Short Password", "short@example.test",
            "eleven-char")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fields.password").value("size must be between 12 and 128"));

    mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registrationWithoutHandle("Valid Password", "valid@example.test",
                "twelve-chars")))
        .andExpect(status().isAccepted());
  }

  @Test
  void pendingRegistrationRequiresVerificationThenCreatesAndRevokesADeviceSession()
      throws Exception {
    MvcResult registration = mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", "register-123")
            .content(registration("pending_creator", "pending@example.test", PASSWORD)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("pending_verification"))
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.verificationRequired").value(true))
        .andExpect(jsonPath("$.testDeliveryId").isNotEmpty())
        .andExpect(cookie().doesNotExist("cs_session"))
        .andReturn();
    JsonNode registrationBody = body(registration);
    String deliveryId = registrationBody.get("testDeliveryId").asText();
    long pendingAccountId = database.queryForObject("select account_id from account_emails "
        + "where email_normalized='pending@example.test'", Long.class);
    String disabledLegacyVerifier = database.queryForObject(
        "select password_hash from creators where id=?", String.class, pendingAccountId);
    String transitionalLegacyVerifier = database.queryForObject(
        "select legacy_verifier from password_credentials where account_id=?", String.class,
        pendingAccountId);
    assertThat(bcrypt.matches(PASSWORD, disabledLegacyVerifier)).isFalse();
    assertThat(transitionalLegacyVerifier).isNull();

    mvc.perform(post("/api/v1/auth/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(login("pending@example.test", PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
        .andExpect(jsonPath("$.emailHint").value("p***@example.test"));
    mvc.perform(post("/api/v1/auth/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(login("missing@example.test", PASSWORD)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").doesNotExist());
    mvc.perform(post("/api/v1/auth/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(login("pending@example.test", "wrong password value")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").doesNotExist());

    mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
            .header("X-Test-Auth-Key", "wrong"))
        .andExpect(status().isNotFound());
    MvcResult delivery = mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
            .header("X-Test-Auth-Key", "identity-e2e-secret"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("email_verification"))
        .andReturn();
    String token = body(delivery).get("token").asText();
    String siblingVerificationToken = SessionCookies.newToken();
    long pendingEmailId = database.queryForObject("select id from account_emails where account_id=?",
        Long.class, pendingAccountId);
    database.update("insert into email_verification_tokens(email_id,token_hash,expires_at) "
            + "values(?,?,?)", pendingEmailId, SessionCookies.hash(siblingVerificationToken),
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

    MvcResult verification = mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(cookie().exists("cs_session"))
        .andReturn();
    Cookie session = verification.getResponse().getCookie("cs_session");
    assertThat(session).isNotNull();
    assertThat(bcrypt.matches(PASSWORD, database.queryForObject(
        "select password_hash from creators where id=?", String.class, pendingAccountId))).isFalse();
    assertThat(passwordService.matches(PASSWORD, database.queryForObject(
        "select verifier from password_credentials where account_id=?", String.class,
        pendingAccountId))).isTrue();
    assertThat(database.queryForObject("select legacy_verifier from password_credentials "
        + "where account_id=?", String.class, pendingAccountId)).isNull();
    mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + siblingVerificationToken + "\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Verification token is invalid or expired."));

    MvcResult listed = mvc.perform(get("/api/v1/auth/sessions").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions.length()").value(1))
        .andExpect(jsonPath("$.sessions[0].publicId").isNotEmpty())
        .andExpect(jsonPath("$.sessions[0].current").value(true))
        .andExpect(jsonPath("$.sessions[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$.sessions[0].lastSeenAt").isNotEmpty())
        .andExpect(jsonPath("$.sessions[0].expiresAt").isNotEmpty())
        .andExpect(jsonPath("$.sessions[0].device").isNotEmpty())
        .andExpect(jsonPath("$.sessions[0].location").value("Not collected"))
        .andReturn();
    UUID sessionId = UUID.fromString(body(listed).at("/sessions/0/publicId").asText());
    long sessionAccountId = database.queryForObject("select account_id from auth_sessions "
        + "where token_hash=?", Long.class, SessionCookies.hash(session.getValue()));
    database.update("insert into sessions(id,creator_id,expires_at) values(?,?,?)",
        SessionCookies.hash(session.getValue()), sessionAccountId,
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

    mvc.perform(delete("/api/v1/auth/sessions/{id}", sessionId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
    assertThat(database.queryForObject("select revoked_at is not null from sessions where id=?",
        Boolean.class, SessionCookies.hash(session.getValue()))).isTrue();
    mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(login("pending@example.test", PASSWORD)))
        .andExpect(status().isOk()).andExpect(cookie().exists("cs_session"));
  }

  @Test
  void passwordResetRequiresVerifiedActiveAccountAndInvalidatesEverySession() throws Exception {
    Cookie session = registerAndVerify("reset_creator", "reset@example.test", PASSWORD);

    MvcResult resetRequest = mvc.perform(post("/api/v1/auth/password-reset-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"reset@example.test\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andReturn();
    String deliveryId = body(resetRequest).get("testDeliveryId").asText();
    MvcResult delivery = mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
            .header("X-Test-Auth-Key", "identity-e2e-secret"))
        .andExpect(status().isOk()).andReturn();
    String token = body(delivery).get("token").asText();
    long resetAccountId = database.queryForObject("select account_id from auth_sessions "
        + "where token_hash=?", Long.class, SessionCookies.hash(session.getValue()));
    database.update("insert into sessions(id,creator_id,expires_at) values(?,?,?)",
        SessionCookies.hash(session.getValue()), resetAccountId,
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
    String siblingResetToken = SessionCookies.newToken();
    database.update("insert into password_reset_tokens(account_id,token_hash,expires_at) "
            + "values(?,?,?)", resetAccountId, SessionCookies.hash(siblingResetToken),
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

    mvc.perform(post("/api/v1/auth/password-resets")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
    assertThat(database.queryForObject("select state from auth_delivery_outbox "
        + "where delivery_id=?", String.class, UUID.fromString(deliveryId))).isEqualTo("dead");
    assertThat(database.queryForObject("select token_value from auth_delivery_outbox "
        + "where delivery_id=?", String.class, UUID.fromString(deliveryId))).isEmpty();
    mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    assertThat(database.queryForObject("select count(*) from sessions where id=? "
            + "and revoked_at is not null", Integer.class,
        SessionCookies.hash(session.getValue()))).isEqualTo(1);
    mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(login("reset@example.test", PASSWORD)))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(login("reset@example.test", NEW_PASSWORD)))
        .andExpect(status().isOk()).andExpect(cookie().exists("cs_session"));
    mvc.perform(post("/api/v1/auth/password-resets")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/auth/password-resets")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + siblingResetToken + "\",\"newPassword\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(database.queryForObject("select count(*) from password_reset_tokens "
        + "where account_id=? and consumed_at is null", Integer.class, resetAccountId)).isZero();
  }

  @Test
  void legacyBcryptAccountAuthenticatesAndUpgradesOnlyAfterSuccessfulLogin() throws Exception {
    String legacy = bcrypt.encode(PASSWORD);
    database.update("insert into creators(handle,display_name,email,password_hash,bio) "
        + "values(?,?,?,?,?)", "legacy_creator", "Legacy Creator", "legacy@example.test",
        legacy, "");
    long id = database.queryForObject("select id from creators where handle='legacy_creator'",
        Long.class);
    identities.provisionExisting(id, "legacy_creator", "Legacy Creator",
        "legacy@example.test", null, legacy);
    assertThat(database.queryForObject("select verified_at is not null from account_emails "
        + "where account_id=?", Boolean.class, id)).isTrue();

    mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(login("legacy@example.test", "wrong password value")))
        .andExpect(status().isUnauthorized());
    assertThat(database.queryForObject(
        "select verifier from password_credentials where account_id=?", String.class, id))
        .startsWith("$2");

    mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(login("legacy@example.test", PASSWORD)))
        .andExpect(status().isOk()).andExpect(cookie().exists("cs_session"));
    assertThat(database.queryForObject(
        "select verifier from password_credentials where account_id=?", String.class, id))
        .startsWith("$argon2");

    MvcResult reset = mvc.perform(post("/api/v1/auth/password-reset-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"legacy@example.test\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.testDeliveryId").isNotEmpty())
        .andReturn();
    String resetToken = deliveryToken(body(reset).get("testDeliveryId").asText());
    mvc.perform(post("/api/v1/auth/password-resets")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + resetToken + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void resetRequestDoesNotCreateAUsableTokenForPendingOrUnknownAccounts() throws Exception {
    mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON)
        .content(registration("still_pending", "still-pending@example.test", PASSWORD)))
        .andExpect(status().isAccepted());

    for (String email : new String[]{"still-pending@example.test", "missing@example.test"}) {
      MvcResult request = mvc.perform(post("/api/v1/auth/password-reset-requests")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"email\":\"" + email + "\"}"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(true)).andReturn();
      assertThat(body(request).has("testDeliveryId")).isFalse();
    }
    assertThat(database.queryForObject("select count(*) from password_reset_tokens",
        Integer.class)).isZero();
  }

  @Test
  void expiredVerificationAndChangedAccountStatesAreRejectedWithoutConsumingTokens()
      throws Exception {
    MvcResult expiredRegistration = mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registration("expired_creator", "expired@example.test", PASSWORD)))
        .andExpect(status().isAccepted()).andReturn();
    String expiredToken = deliveryToken(body(expiredRegistration).get("testDeliveryId").asText());
    database.update("update email_verification_tokens set expires_at=current_timestamp "
        + "where token_hash=?", SessionCookies.hash(expiredToken));
    mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + expiredToken + "\"}"))
        .andExpect(status().isBadRequest());

    for (String statusValue : new String[]{"suspended", "deleted"}) {
      String handle = statusValue + "_creator";
      String email = statusValue + "@example.test";
      MvcResult registration = mvc.perform(post("/api/v1/auth/registrations")
              .contentType(MediaType.APPLICATION_JSON)
              .content(registration(handle, email, PASSWORD)))
          .andExpect(status().isAccepted()).andReturn();
      String token = deliveryToken(body(registration).get("testDeliveryId").asText());
      long accountId = database.queryForObject("select account_id from account_emails "
          + "where email_normalized=?", Long.class, email);
      database.update("update accounts set status=?,deleted_at=case when ?='deleted' "
          + "then current_timestamp else null end where id=?", statusValue, statusValue, accountId);

      mvc.perform(post("/api/v1/auth/email-verifications")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"token\":\"" + token + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("Verification token is invalid or expired."));
      assertThat(database.queryForObject("select consumed_at is null from "
          + "email_verification_tokens where token_hash=?", Boolean.class,
          SessionCookies.hash(token))).isTrue();
      assertThat(bcrypt.matches(PASSWORD, database.queryForObject(
          "select password_hash from creators where id=?", String.class, accountId))).isFalse();
    }
  }

  @Test
  void passwordResetRechecksStatusAndVerifiedEmailUnderTheTransactionLock() throws Exception {
    registerAndVerify("status_reset", "status-reset@example.test", PASSWORD);
    MvcResult resetRequest = mvc.perform(post("/api/v1/auth/password-reset-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"status-reset@example.test\"}"))
        .andExpect(status().isAccepted()).andReturn();
    String token = deliveryToken(body(resetRequest).get("testDeliveryId").asText());
    long accountId = database.queryForObject("select account_id from account_emails "
        + "where email_normalized='status-reset@example.test'", Long.class);
    database.update("update accounts set status='suspended' where id=?", accountId);

    mvc.perform(post("/api/v1/auth/password-resets")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error")
            .value("Password-reset token is invalid or expired."));
    assertThat(database.queryForObject("select consumed_at is null from password_reset_tokens "
        + "where token_hash=?", Boolean.class, SessionCookies.hash(token))).isTrue();
  }

  @Test
  void sessionListingExcludesIdleExpiredAndWrongVersionSessions() throws Exception {
    Cookie first = registerAndVerify("session_filter", "session-filter@example.test", PASSWORD);
    MvcResult secondLogin = mvc.perform(post("/api/v1/auth/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(login("session-filter@example.test", PASSWORD)))
        .andExpect(status().isOk()).andReturn();
    Cookie second = secondLogin.getResponse().getCookie("cs_session");
    database.update("update auth_sessions set idle_expires_at=current_timestamp "
        + "where token_hash=?", SessionCookies.hash(first.getValue()));

    mvc.perform(get("/api/v1/auth/sessions").cookie(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions.length()").value(1))
        .andExpect(jsonPath("$.sessions[0].current").value(true));
    database.update("update auth_sessions set idle_expires_at=?,session_version=session_version-1 "
            + "where token_hash=?", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
        SessionCookies.hash(first.getValue()));
    mvc.perform(get("/api/v1/auth/sessions").cookie(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions.length()").value(1));
  }

  @Test
  void aLegacyOnlyLogoutIsObservedByTheNewSessionResolver() throws Exception {
    Cookie session = registerAndVerify("legacy_logout", "legacy-logout@example.test", PASSWORD);
    String tokenHash = SessionCookies.hash(session.getValue());
    long accountId = database.queryForObject("select account_id from auth_sessions "
        + "where token_hash=?", Long.class, tokenHash);
    database.update("insert into sessions(id,creator_id,expires_at,revoked_at) values(?,?,?,?)",
        tokenHash, accountId, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
        OffsetDateTime.now(ZoneOffset.UTC));

    mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    assertThat(database.queryForObject("select revoked_at is null from auth_sessions "
        + "where token_hash=?", Boolean.class, tokenHash)).isTrue();
  }

  @Test
  void oversizedUntrustedRequestIdCannotBreakRegistrationOrReachTheAuditColumn()
      throws Exception {
    mvc.perform(post("/api/v1/auth/registrations")
            .header("X-Request-Id", "x".repeat(500))
            .contentType(MediaType.APPLICATION_JSON)
            .content(registration("bounded_request", "bounded@example.test", PASSWORD)))
        .andExpect(status().isAccepted());
    assertThat(database.queryForObject("select request_id from auth_security_events "
        + "where event_type='ACCOUNT_REGISTERED'", String.class)).isNull();
  }

  @Test
  void corsPreflightIsAllowedButTheProtectedActualSessionRequestStillRequiresAuth()
      throws Exception {
    for (String path : new String[]{"/api/v1/auth/registrations", "/api/v1/auth/sessions",
        "/api/v1/auth/password-resets"}) {
      mvc.perform(options(path)
              .header("Origin", "http://localhost:5173")
              .header("Access-Control-Request-Method", "POST")
              .header("Access-Control-Request-Headers", "content-type"))
          .andExpect(status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
              .string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
    mvc.perform(options("/api/v1/auth/sessions")
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/sessions"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void compatibilityLoginRejectsOversizedInputsBeforeCredentialWork() throws Exception {
    mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(login("a".repeat(321), "p".repeat(129))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fields.handleOrEmail").exists())
        .andExpect(jsonPath("$.fields.password").exists());
  }

  @Test
  void aNewVerificationDeliverySupersedesTheOlderOutboxSecretAtomically() throws Exception {
    MvcResult registration = mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registration("resend_owner", "resend-owner@example.test", PASSWORD)))
        .andExpect(status().isAccepted()).andReturn();
    UUID firstDelivery = UUID.fromString(body(registration).get("testDeliveryId").asText());

    MvcResult resend = mvc.perform(post("/api/v1/auth/email-verifications/resend")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"resend-owner@example.test\"}"))
        .andExpect(status().isAccepted()).andReturn();
    UUID secondDelivery = UUID.fromString(body(resend).get("testDeliveryId").asText());

    assertThat(secondDelivery).isNotEqualTo(firstDelivery);
    assertThat(database.queryForObject("select state from auth_delivery_outbox "
        + "where delivery_id=?", String.class, firstDelivery)).isEqualTo("dead");
    assertThat(database.queryForObject("select token_value from auth_delivery_outbox "
        + "where delivery_id=?", String.class, firstDelivery)).isEmpty();
    mvc.perform(get("/api/v1/auth/test-deliveries/{id}", firstDelivery)
            .header("X-Test-Auth-Key", "identity-e2e-secret"))
        .andExpect(status().isNotFound());

    String currentToken = deliveryToken(secondDelivery.toString());
    mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + currentToken + "\"}"))
        .andExpect(status().isOk());
    assertThat(database.queryForObject("select state from auth_delivery_outbox "
        + "where delivery_id=?", String.class, secondDelivery)).isEqualTo("dead");
    assertThat(database.queryForObject("select token_value from auth_delivery_outbox "
        + "where delivery_id=?", String.class, secondDelivery)).isEmpty();
  }

  @Test
  void anExpiredLeaseOwnerCannotOverwriteTheCurrentWorkersCompletion() {
    String legacy = bcrypt.encode(PASSWORD);
    database.update("insert into creators(handle,display_name,email,password_hash,bio) "
            + "values(?,?,?,?,?)", "lease_owner", "Lease Owner", "lease-owner@example.test",
        legacy, "");
    long accountId = database.queryForObject("select id from creators where handle=?", Long.class,
        "lease_owner");
    identities.provisionExisting(accountId, "lease_owner", "Lease Owner",
        "lease-owner@example.test", null, legacy);
    UUID deliveryId = authDeliveries.enqueue(accountId, "password_reset",
        "lease-owner@example.test", "one-time-secret",
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30));

    AuthDeliveryRepository.Delivery firstClaim = authDeliveries.claim().orElseThrow();
    database.update("update auth_delivery_outbox set lease_until=? where delivery_id=?",
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), deliveryId);
    AuthDeliveryRepository.Delivery secondClaim = authDeliveries.claim().orElseThrow();

    assertThat(secondClaim.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
    assertThat(secondClaim.attempts()).isEqualTo(firstClaim.attempts() + 1);
    assertThat(authDeliveries.markFailed(firstClaim, "stale worker failed")).isFalse();
    assertThat(authDeliveries.markSent(firstClaim)).isFalse();
    assertThat(authDeliveries.markSent(secondClaim)).isTrue();
    assertThat(database.queryForObject("select state from auth_delivery_outbox "
        + "where delivery_id=?", String.class, deliveryId)).isEqualTo("sent");
    assertThat(database.queryForObject("select token_value from auth_delivery_outbox "
        + "where delivery_id=?", String.class, deliveryId)).isEmpty();
  }

  @Test
  void concurrentResetRequestsSerializeToOneActiveAccountToken() throws Exception {
    String legacy = bcrypt.encode(PASSWORD);
    database.update("insert into creators(handle,display_name,email,password_hash,bio) "
        + "values(?,?,?,?,?)", "concurrent_reset", "Concurrent Reset",
        "concurrent-reset@example.test", legacy, "");
    long accountId = database.queryForObject("select id from creators where handle=?",
        Long.class, "concurrent_reset");
    identities.provisionExisting(accountId, "concurrent_reset", "Concurrent Reset",
        "concurrent-reset@example.test", null, legacy);

    int workerCount = 4;
    var executor = Executors.newFixedThreadPool(workerCount);
    CountDownLatch ready = new CountDownLatch(workerCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < workerCount; index++) {
        String rawToken = "concurrent-reset-token-" + index;
        String tokenHash = SessionCookies.hash(rawToken);
        futures.add(executor.submit(() -> {
          ready.countDown();
          start.await();
          challenges.createPasswordReset("concurrent-reset@example.test", rawToken, tokenHash,
              OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30));
          return null;
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(database.queryForObject("select count(*) from password_reset_tokens "
        + "where account_id=? and consumed_at is null", Integer.class, accountId)).isEqualTo(1);
    assertThat(database.queryForObject("select count(*) from auth_delivery_outbox "
        + "where account_id=? and delivery_type='password_reset' and state='pending'",
        Integer.class, accountId)).isEqualTo(1);
    assertThat(database.queryForObject("select count(*) from auth_delivery_outbox "
        + "where account_id=? and delivery_type='password_reset' and state='dead' "
        + "and token_value=''", Integer.class, accountId)).isEqualTo(workerCount - 1);
  }

  @Test
  void resetAfterInitialPasswordCheckPreventsOldCredentialFromFinalizingALogin() {
    String legacy = bcrypt.encode(PASSWORD);
    database.update("insert into creators(handle,display_name,email,password_hash,bio) "
        + "values(?,?,?,?,?)", "login_reset_race", "Login Reset Race",
        "login-reset-race@example.test", legacy, "");
    long accountId = database.queryForObject("select id from creators where handle=?",
        Long.class, "login_reset_race");
    identities.provisionExisting(accountId, "login_reset_race", "Login Reset Race",
        "login-reset-race@example.test", null, legacy);

    IdentityRepository.LoginAccount initiallyRead = identities.findForLogin(
        "login-reset-race@example.test").orElseThrow();
    assertThat(passwordService.matches(PASSWORD, initiallyRead.verifier())).isTrue();
    String staleUpgradeCandidate = passwordService.encode(PASSWORD);

    String resetHash = SessionCookies.hash("deterministic-reset-between-login-phases");
    challenges.createPasswordReset("login-reset-race@example.test",
        "deterministic-reset-between-login-phases", resetHash,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30));
    challenges.resetPassword(resetHash, passwordService.encode(NEW_PASSWORD),
        passwordService.legacyVerifier(NEW_PASSWORD));

    IdentityLoginTransactionService.Result finalized = loginTransactions.finalizeLogin(
        accountId, initiallyRead.verifier(), PASSWORD, staleUpgradeCandidate,
        ClientContext.unknown());

    assertThat(finalized.outcome()).isEqualTo(IdentityLoginTransactionService.Outcome.INVALID);
    assertThat(finalized.session()).isNull();
    assertThat(database.queryForObject("select count(*) from auth_sessions where account_id=?",
        Integer.class, accountId)).isZero();
    String currentVerifier = database.queryForObject("select verifier from password_credentials "
        + "where account_id=?", String.class, accountId);
    assertThat(passwordService.matches(NEW_PASSWORD, currentVerifier)).isTrue();
    assertThat(passwordService.matches(PASSWORD, currentVerifier)).isFalse();
  }

  private Cookie registerAndVerify(String handle, String email, String password) throws Exception {
    MvcResult registration = mvc.perform(post("/api/v1/auth/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registration(handle, email, password)))
        .andExpect(status().isAccepted()).andReturn();
    String deliveryId = body(registration).get("testDeliveryId").asText();
    MvcResult delivery = mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
            .header("X-Test-Auth-Key", "identity-e2e-secret"))
        .andExpect(status().isOk()).andReturn();
    String token = body(delivery).get("token").asText();
    MvcResult verified = mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk()).andReturn();
    return verified.getResponse().getCookie("cs_session");
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private String deliveryToken(String deliveryId) throws Exception {
    MvcResult delivery = mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
            .header("X-Test-Auth-Key", "identity-e2e-secret"))
        .andExpect(status().isOk()).andReturn();
    return body(delivery).get("token").asText();
  }

  private static String registration(String handle, String email, String password) {
    return """
        {"handle":"%s","displayName":"Test Creator","email":"%s","phone":null,
         "password":"%s"}
        """.formatted(handle, email, password);
  }

  private static String registrationWithoutHandle(String displayName, String email,
                                                   String password) {
    return """
        {"displayName":"%s","email":"%s","password":"%s"}
        """.formatted(displayName, email, password);
  }

  private static String login(String identifier, String password) {
    return "{\"handleOrEmail\":\"" + identifier + "\",\"password\":\""
        + password + "\"}";
  }
}
