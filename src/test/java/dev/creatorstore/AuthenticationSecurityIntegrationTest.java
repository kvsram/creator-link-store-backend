package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.bootstrap.DemoDataInitializer;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.SessionCookies;
import dev.creatorstore.identity.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
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
    "spring.datasource.url=jdbc:h2:mem:auth-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "spring.flyway.enabled=false",
    "instagram.autodm-worker-enabled=false",
    "app.auth.delivery-worker-enabled=false",
    "app.security.csrf-enabled=true",
    "app.allowed-origins=http://localhost:5173"
})
@AutoConfigureMockMvc
@Sql("/auth-integration-schema.sql")
class AuthenticationSecurityIntegrationTest {
  private static final String EMAIL = "security@example.test";
  private static final String PASSWORD = "correct horse battery staple";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcTemplate database;
  @Autowired private IdentityRepository identities;
  @Autowired private SessionService sessions;
  @Autowired private BCryptPasswordEncoder bcrypt;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

  private long accountId;
  private Cookie sessionCookie;

  @BeforeEach
  void createActiveCreatorAndSession() {
    String verifier = bcrypt.encode(PASSWORD);
    database.update("insert into creators(handle,display_name,email,password_hash,bio) "
            + "values(?,?,?,?,?)", "security_creator", "Security Creator", EMAIL, verifier, "");
    accountId = database.queryForObject("select id from creators where handle=?", Long.class,
        "security_creator");
    identities.provisionExisting(accountId, "security_creator", "Security Creator", EMAIL,
        null, verifier);
    SessionService.IssuedSession session = sessions.issue(accountId,
        new ClientContext("203.0.113.9", "security-test", "security-test-request"), "password");
    sessionCookie = new Cookie(SessionCookies.COOKIE_NAME, session.token());
  }

  @Test
  void anonymousProtectedRequestReturnsJson401WithoutRedirectOrServletSession() throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(header().doesNotExist("Location"))
        .andExpect(cookie().doesNotExist("JSESSIONID"));
  }

  @Test
  void csrfBootstrapIssuesTokenAndUnsafeAuthenticationRequestsRequireIt() throws Exception {
    MvcResult bootstrap = mvc.perform(get("/api/v1/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
        .andExpect(jsonPath("$.parameterName").value("_csrf"))
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(cookie().exists("XSRF-TOKEN"))
        .andExpect(cookie().doesNotExist("JSESSIONID"))
        .andReturn();

    mvc.perform(post("/api/v1/auth/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

    JsonNode csrf = json.readTree(bootstrap.getResponse().getContentAsString());
    Cookie csrfCookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    mvc.perform(post("/api/v1/auth/sessions")
            .cookie(csrfCookie)
            .header(csrf.get("headerName").asText(), csrf.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(accountId))
        .andExpect(cookie().exists(SessionCookies.COOKIE_NAME))
        .andExpect(cookie().doesNotExist("JSESSIONID"));
  }

  @Test
  void opaqueSessionPopulatesCreatorPrincipalForAuthenticatedControllers() throws Exception {
    mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(accountId))
        .andExpect(jsonPath("$.handle").value("security_creator"))
        .andExpect(jsonPath("$.email").value(EMAIL))
        .andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(cookie().doesNotExist("JSESSIONID"));

    mvc.perform(get("/api/v1/auth/sessions").cookie(sessionCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions.length()").value(1))
        .andExpect(jsonPath("$.sessions[0].current").value(true));
  }

  @Test
  void csrfProtectsAuthenticatedSessionMutationBeforeLogoutIsCommitted() throws Exception {
    mvc.perform(delete("/api/v1/auth/sessions/current").cookie(sessionCookie))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
        .andExpect(status().isOk());

    MvcResult bootstrap = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
        .andReturn();
    JsonNode csrf = json.readTree(bootstrap.getResponse().getContentAsString());
    Cookie csrfCookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    mvc.perform(delete("/api/v1/auth/sessions/current")
            .cookie(sessionCookie, csrfCookie)
            .header(csrf.get("headerName").asText(), csrf.get("token").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(cookie().maxAge(SessionCookies.COOKIE_NAME, 0))
        .andExpect(cookie().doesNotExist("JSESSIONID"));
    mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
        .andExpect(status().isUnauthorized());
  }

  private static String loginBody() {
    return "{\"handleOrEmail\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
  }
}
