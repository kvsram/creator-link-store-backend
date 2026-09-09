package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.creatorstore.bootstrap.DemoDataInitializer;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
    "spring.datasource.url=jdbc:h2:mem:registration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "instagram.autodm-worker-enabled=false"
})
@AutoConfigureMockMvc
@Sql("/auth-integration-schema.sql")
class RegistrationSessionIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate database;
  @Autowired private BCryptPasswordEncoder passwords;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

  @Test
  void registrationPersistsDefaultsAndImmediatelyAuthenticatesTheCreator() throws Exception {
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
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.handle").value("new_creator"))
        .andExpect(jsonPath("$.displayName").value("New Creator"))
        .andExpect(jsonPath("$.plan").value("free"))
        .andExpect(jsonPath("$.authenticated").value(true))
        .andReturn();

    Cookie sessionCookie = registration.getResponse().getCookie("cs_session");
    assertThat(sessionCookie).isNotNull();
    assertThat(sessionCookie.isHttpOnly()).isTrue();
    assertThat(sessionCookie.getPath()).isEqualTo("/");
    assertThat(sessionCookie.getValue()).isNotBlank();

    Map<String, Object> creator = database.queryForMap(
        "select id,handle,display_name,email,phone,password_hash from creators where handle=?",
        "new_creator");
    long creatorId = ((Number) creator.get("id")).longValue();
    assertThat(creator).containsEntry("display_name", "New Creator")
        .containsEntry("email", "creator@example.com")
        .containsEntry("phone", null);
    assertThat(creator.get("password_hash")).isNotEqualTo("strong-test-password");
    assertThat(passwords.matches("strong-test-password",
        String.valueOf(creator.get("password_hash")))).isTrue();

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
    assertThat(database.queryForObject(
        "select count(*) from sessions where creator_id=?", Integer.class, creatorId)).isEqualTo(1);
    assertThat(database.queryForObject(
        "select count(*) from sessions where id=?", Integer.class, sessionCookie.getValue()))
        .isZero();

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
