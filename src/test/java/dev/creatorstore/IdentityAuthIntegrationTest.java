package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.bootstrap.DemoDataInitializer;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.SessionCookies;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
  @Autowired private BCryptPasswordEncoder bcrypt;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

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
    assertThat(bcrypt.matches(PASSWORD, transitionalLegacyVerifier)).isTrue();

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
        "select password_hash from creators where id=?", String.class, pendingAccountId))).isTrue();
    assertThat(database.queryForObject("select legacy_verifier from password_credentials "
        + "where account_id=?", String.class, pendingAccountId)).isNull();

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

    mvc.perform(post("/api/v1/auth/password-resets")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
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
      String deliveryId = body(request).get("testDeliveryId").asText();
      MvcResult delivery = mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
              .header("X-Test-Auth-Key", "identity-e2e-secret"))
          .andExpect(status().isOk()).andReturn();
      String token = body(delivery).get("token").asText();
      mvc.perform(post("/api/v1/auth/password-resets")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"token\":\"" + token + "\",\"newPassword\":\""
                  + NEW_PASSWORD + "\"}"))
          .andExpect(status().isBadRequest());
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

  private static String login(String identifier, String password) {
    return "{\"handleOrEmail\":\"" + identifier + "\",\"password\":\""
        + password + "\"}";
  }
}
