package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.creatorstore.bootstrap.DemoDataInitializer;
import dev.creatorstore.identity.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:registration-atomicity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "instagram.autodm-worker-enabled=false",
    "app.auth.test-delivery-enabled=true",
    "app.auth.test-delivery-key=registration-atomicity-secret"
})
@AutoConfigureMockMvc
@Sql("/auth-integration-schema.sql")
class RegistrationAtomicityIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate database;

  @MockitoBean private DemoDataInitializer demoDataInitializer;
  @MockitoBean private SessionService sessions;

  @Test
  void compatibilityRegistrationIsPendingAndCannotIssueASession() throws Exception {
    mvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"atomic_creator","displayName":"Atomic Creator",
                 "email":"atomic@example.test","phone":null,
                 "password":"strong-test-password"}
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("pending_verification"))
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.verificationRequired").value(true))
        .andExpect(cookie().doesNotExist("cs_session"));

    assertThat(database.queryForObject("select count(*) from creators where handle=?",
        Integer.class, "atomic_creator")).isOne();
    assertThat(database.queryForObject("select count(*) from accounts", Integer.class)).isOne();
    assertThat(database.queryForObject("select count(*) from stores", Integer.class)).isOne();
    assertThat(database.queryForObject("select count(*) from notification_preferences",
        Integer.class)).isOne();
    assertThat(database.queryForObject("select count(*) from auth_sessions",
        Integer.class)).isZero();
    verify(sessions, never()).issue(anyLong(), any(), anyString());
  }
}
