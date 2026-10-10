package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.creatorstore.bootstrap.DemoDataInitializer;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
    "spring.datasource.url=jdbc:h2:mem:registration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "instagram.autodm-worker-enabled=false",
    "app.auth.test-delivery-enabled=true",
    "app.auth.test-delivery-key=compatibility-e2e-secret"
})
@AutoConfigureMockMvc
@Sql("/auth-integration-schema.sql")
class RegistrationSessionIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate database;
  @Autowired private BCryptPasswordEncoder passwords;
  @Autowired private ObjectMapper json;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

  @Test
  void compatibilityRegistrationPersistsDefaultsButRequiresEmailVerification() throws Exception {
    MvcResult registration = mvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "handle": " New_Creator ",
                  "displayName": " New Creator ",
                  "email": " Creator@Example.com ",
                  "phone": " ",
                  "password": "strong-test-password"
                }
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.handle").value("new_creator"))
        .andExpect(jsonPath("$.displayName").value("New Creator"))
        .andExpect(jsonPath("$.plan").value("free"))
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.verificationRequired").value(true))
        .andReturn();

    assertThat(registration.getResponse().getCookie("cs_session")).isNull();
    JsonNode registrationBody = json.readTree(registration.getResponse().getContentAsString());
    String deliveryId = registrationBody.get("testDeliveryId").asText();

    Map<String, Object> creator = database.queryForMap(
        "select id,handle,display_name,email,phone,password_hash from creators where handle=?",
        "new_creator");
    long creatorId = ((Number) creator.get("id")).longValue();
    assertThat(creator).containsEntry("display_name", "New Creator")
        .containsEntry("email", "creator@example.com")
        .containsEntry("phone", null);
    assertThat(creator.get("password_hash")).isNotEqualTo("strong-test-password");
    assertThat(passwords.matches("strong-test-password",
        String.valueOf(creator.get("password_hash")))).isFalse();

    assertThat(database.queryForMap(
        "select title,currency,published,payouts_enabled from stores where creator_id=?",
        creatorId))
        .containsEntry("title", "New Creator's Store")
        .containsEntry("currency", "INR")
        .containsEntry("published", true)
        .containsEntry("payouts_enabled", false);
    assertThat(database.queryForObject(
        "select count(*) from notification_preferences where creator_id=?", Integer.class,
        creatorId)).isEqualTo(1);
    assertThat(database.queryForObject("select verified_at is not null from account_emails "
        + "where account_id=? and is_primary=true", Boolean.class, creatorId)).isFalse();
    assertThat(database.queryForObject(
        "select count(*) from auth_sessions where account_id=?", Integer.class, creatorId)).isZero();

    MvcResult delivery = mvc.perform(get("/api/v1/auth/test-deliveries/{id}", deliveryId)
            .header("X-Test-Auth-Key", "compatibility-e2e-secret"))
        .andExpect(status().isOk())
        .andReturn();
    String token = json.readTree(delivery.getResponse().getContentAsString()).get("token").asText();
    MvcResult verification = mvc.perform(post("/api/v1/auth/email-verifications")
            .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andReturn();
    Cookie sessionCookie = verification.getResponse().getCookie("cs_session");
    assertThat(sessionCookie).isNotNull();
    assertThat(sessionCookie.isHttpOnly()).isTrue();
    assertThat(sessionCookie.getPath()).isEqualTo("/");
    assertThat(sessionCookie.getValue()).isNotBlank();
    assertThat(database.queryForObject("select verified_at is not null from account_emails "
        + "where account_id=? and is_primary=true", Boolean.class, creatorId)).isTrue();
    assertThat(database.queryForObject(
        "select count(*) from auth_sessions where account_id=?", Integer.class, creatorId)).isOne();

    mvc.perform(get("/api/auth/me").cookie(sessionCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(creatorId))
        .andExpect(jsonPath("$.handle").value("new_creator"))
        .andExpect(jsonPath("$.displayName").value("New Creator"))
        .andExpect(jsonPath("$.email").value("creator@example.com"));

    mvc.perform(post("/api/v1/authentication/check-unique-taken")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"another_handle","email":"creator@example.com"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handle_taken").value(false))
        .andExpect(jsonPath("$.username_taken").value(false))
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.email_taken").doesNotExist());

    MvcResult login = mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handleOrEmail":"creator@example.com","password":"strong-test-password"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(creatorId))
        .andExpect(jsonPath("$.handle").value("new_creator"))
        .andReturn();
    Cookie loginCookie = login.getResponse().getCookie("cs_session");
    assertThat(loginCookie).isNotNull();
    assertThat(loginCookie.getValue()).isNotEqualTo(sessionCookie.getValue());

    mvc.perform(get("/api/auth/me").cookie(loginCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handle").value("new_creator"));
  }
}
