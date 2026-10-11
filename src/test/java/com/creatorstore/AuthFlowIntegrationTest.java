package com.creatorstore;

import com.creatorstore.notification.SmsSender;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the whole application against a real PostgreSQL 16 (Testcontainers), lets Flyway create the
 * schema, and walks the creator flows end to end over HTTP:
 * signup -> phone link by SMS -> confirm -> login -> /me -> refresh (rotation) -> reuse detection -> logout,
 * plus the admin bootstrap and admin login.
 *
 * Requires Docker (GitHub-hosted runners and developer laptops have it).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.secret=integration-test-secret-at-least-32-characters-long",
                "app.admin.bootstrap.email=admin@example.test",
                "app.admin.bootstrap.password=bootstrap-password-123",
                "app.admin.bootstrap.full-name=Test Admin"
        })
@Import(AuthFlowIntegrationTest.CapturingSms.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Replaces the logging SMS sender so the test can read the verification link. */
    @TestConfiguration
    static class CapturingSms {
        static final List<String> messages = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        SmsSender capturingSmsSender() {
            return (to, message) -> messages.add(to + "|" + message);
        }
    }

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    TestRestTemplate http;

    // Shared across the ordered steps of one scenario
    static String accessToken;
    static String refreshToken;

    @Test
    @Order(0)
    void healthIsPublicAndSaysOnlyUp() {
        Map health = get("/actuator/health");
        assertEquals("UP", health.get("status"));
        assertFalse(health.containsKey("components"), "health must not leak component details");
        assertEquals(HttpStatus.OK, http.getForEntity("/actuator/health/readiness", Map.class).getStatusCode());
        // the rest of the actuator stays closed
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/actuator/env", Map.class).getStatusCode());
    }

    @Test
    @Order(1)
    void usernameAvailabilityIsCaseInsensitiveAndRejectsReservedNames() {
        assertEquals(true, get("/api/auth/username-availability?username=SkinGlow").get("available"));
        assertEquals(false, get("/api/auth/username-availability?username=admin").get("available"));
        assertEquals(false, get("/api/auth/username-availability?username=ab").get("available"));
    }

    @Test
    @Order(2)
    void signupNormalisesInputAndSendsOneSms() {
        ResponseEntity<Map> response = post("/api/auth/signup", Map.of(
                "fullName", "  Priya   Sharma ",
                "email", "Priya@Example.COM",
                "username", "SkinGlow",
                "password", "StrongPass123",
                "phoneNumber", "+91 98765-43210"), null, Map.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("skinglow", response.getBody().get("username"));
        assertEquals(true, response.getBody().get("phoneVerificationSent"));
        assertEquals(1, CapturingSms.messages.size());
        assertTrue(CapturingSms.messages.get(0).startsWith("+919876543210|"));
    }

    @Test
    @Order(3)
    void duplicateEmailOrUsernameIsRejectedRegardlessOfCase() {
        ResponseEntity<Map> sameEmail = post("/api/auth/signup", Map.of(
                "fullName", "Other Person", "email", "PRIYA@example.com",
                "username", "other", "password", "StrongPass123"), null, Map.class);
        assertEquals(HttpStatus.CONFLICT, sameEmail.getStatusCode());
        assertEquals("EMAIL_TAKEN", sameEmail.getBody().get("code"));

        ResponseEntity<Map> sameUsername = post("/api/auth/signup", Map.of(
                "fullName", "Other Person", "email", "other@example.com",
                "username", "SKINGLOW", "password", "StrongPass123"), null, Map.class);
        assertEquals(HttpStatus.CONFLICT, sameUsername.getStatusCode());
        assertEquals("USERNAME_TAKEN", sameUsername.getBody().get("code"));
    }

    @Test
    @Order(4)
    void invalidFullNameIsRejectedByValidation() {
        ResponseEntity<Map> response = post("/api/auth/signup", Map.of(
                "fullName", "Priya123", "email", "x@example.com",
                "username", "xuser", "password", "StrongPass123"), null, Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().get("code"));
    }

    @Test
    @Order(5)
    void phoneLinkConfirmsOnceAndRejectsReuse() {
        Matcher m = TOKEN_IN_LINK.matcher(CapturingSms.messages.get(0));
        assertTrue(m.find(), "SMS must contain the verification link");
        String token = m.group(1);

        ResponseEntity<Map> first = post("/api/verification/phone/confirm", Map.of("token", token), null, Map.class);
        assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode());

        ResponseEntity<Map> second = post("/api/verification/phone/confirm", Map.of("token", token), null, Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, second.getStatusCode());
        assertEquals("LINK_USED_OR_REPLACED", second.getBody().get("code"));

        ResponseEntity<Map> garbage = post("/api/verification/phone/confirm", Map.of("token", "not-a-token"), null, Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, garbage.getStatusCode());
        assertEquals("INVALID_TOKEN", garbage.getBody().get("code"));
    }

    @Test
    @Order(6)
    void loginWorksByUsernameOrEmailAndHidesWhetherAccountsExist() {
        ResponseEntity<Map> wrong = post("/api/auth/login", Map.of(
                "identifier", "skinglow", "password", "wrong-password"), null, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, wrong.getStatusCode());
        assertEquals("INVALID_CREDENTIALS", wrong.getBody().get("code"));

        ResponseEntity<Map> unknown = post("/api/auth/login", Map.of(
                "identifier", "nobody@example.com", "password", "whatever-123"), null, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.getStatusCode());
        assertEquals("INVALID_CREDENTIALS", unknown.getBody().get("code"));

        ResponseEntity<Map> ok = post("/api/auth/login", Map.of(
                "identifier", "priya@example.com", "password", "StrongPass123"), null, Map.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        accessToken = (String) ok.getBody().get("accessToken");
        refreshToken = (String) ok.getBody().get("refreshToken");
        assertNotNull(accessToken);
        assertNotNull(refreshToken);
        assertEquals("Bearer", ok.getBody().get("tokenType"));
    }

    @Test
    @Order(7)
    void profileRequiresAValidTokenAndShowsVerifiedPhone() {
        ResponseEntity<Map> anonymous = http.getForEntity("/api/creators/me", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode());
        assertEquals("UNAUTHORIZED", anonymous.getBody().get("code"));

        ResponseEntity<Map> me = exchange(HttpMethod.GET, "/api/creators/me", null, accessToken, Map.class);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals("skinglow", me.getBody().get("username"));
        assertEquals("priya@example.com", me.getBody().get("email"));
        assertEquals("Priya Sharma", me.getBody().get("fullName"));
        assertEquals("+919876543210", me.getBody().get("phoneNumber"));
        assertEquals(true, me.getBody().get("phoneVerified"));
        assertFalse(me.getBody().containsKey("passwordHash"));
    }

    @Test
    @Order(8)
    void refreshRotatesAndReuseRevokesTheFamily() {
        ResponseEntity<Map> rotated = post("/api/auth/refresh", Map.of("refreshToken", refreshToken), null, Map.class);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        String newRefresh = (String) rotated.getBody().get("refreshToken");
        assertNotNull(newRefresh);
        assertFalse(newRefresh.equals(refreshToken), "refresh token must rotate");

        // Presenting the OLD token again is reuse: the whole family is revoked.
        ResponseEntity<Map> reuse = post("/api/auth/refresh", Map.of("refreshToken", refreshToken), null, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, reuse.getStatusCode());
        assertEquals("REFRESH_TOKEN_REUSED", reuse.getBody().get("code"));

        // ... so the NEW token is dead too.
        ResponseEntity<Map> afterReuse = post("/api/auth/refresh", Map.of("refreshToken", newRefresh), null, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, afterReuse.getStatusCode());
        assertEquals("REFRESH_TOKEN_REUSED", afterReuse.getBody().get("code"));
    }

    @Test
    @Order(9)
    void staleAccessTokenOnAPublicEndpointDoesNotBreakIt() {
        // A client that still sends an Authorization header to /login must not get 401 from the token filter.
        ResponseEntity<Map> ok = post("/api/auth/login", Map.of(
                "identifier", "skinglow", "password", "StrongPass123"), "definitely-not-a-jwt", Map.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        refreshToken = (String) ok.getBody().get("refreshToken");
        accessToken = (String) ok.getBody().get("accessToken");
    }

    @Test
    @Order(10)
    void logoutRevokesTheRefreshTokenAndIsIdempotent() {
        ResponseEntity<Void> first = post("/api/auth/logout", Map.of("refreshToken", refreshToken), null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode());
        ResponseEntity<Void> again = post("/api/auth/logout", Map.of("refreshToken", refreshToken), null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, again.getStatusCode());

        ResponseEntity<Map> refresh = post("/api/auth/refresh", Map.of("refreshToken", refreshToken), null, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, refresh.getStatusCode());
    }

    @Test
    @Order(11)
    void creatorTokenCannotReachAdminAndAdminBootstrapWorks() {
        ResponseEntity<Map> forbidden = exchange(HttpMethod.GET, "/api/admin/me", null, accessToken, Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        ResponseEntity<Map> adminLogin = post("/api/admin/auth/login", Map.of(
                "email", "Admin@Example.test", "password", "bootstrap-password-123"), null, Map.class);
        assertEquals(HttpStatus.OK, adminLogin.getStatusCode());
        assertEquals("SUPER_ADMIN", adminLogin.getBody().get("role"));

        ResponseEntity<Map> adminMe = exchange(HttpMethod.GET, "/api/admin/me",
                null, (String) adminLogin.getBody().get("accessToken"), Map.class);
        assertEquals(HttpStatus.OK, adminMe.getStatusCode());
        assertEquals("admin@example.test", adminMe.getBody().get("email"));

        // An admin token is not a creator token.
        ResponseEntity<Map> adminOnCreatorRoute = exchange(HttpMethod.GET, "/api/creators/me",
                null, (String) adminLogin.getBody().get("accessToken"), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, adminOnCreatorRoute.getStatusCode());
    }

    @Test
    @Order(12)
    void lockoutAfterFiveFailedAttempts() {
        post("/api/auth/signup", Map.of(
                "fullName", "Lock Me", "email", "lock@example.com",
                "username", "lockme", "password", "StrongPass123"), null, Map.class);
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> r = post("/api/auth/login", Map.of("identifier", "lockme", "password", "bad-" + i), null, Map.class);
            assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode(), "attempt " + (i + 1));
        }
        ResponseEntity<Map> locked = post("/api/auth/login", Map.of("identifier", "lockme", "password", "StrongPass123"), null, Map.class);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, locked.getStatusCode());
        assertEquals("ACCOUNT_LOCKED", locked.getBody().get("code"));
    }

    // ---- helpers -------------------------------------------------------

    private Map get(String path) {
        ResponseEntity<Map> r = http.getForEntity(path, Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String bearer, Class<T> type) {
        return exchange(HttpMethod.POST, path, body, bearer, type);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body, String bearer, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}
