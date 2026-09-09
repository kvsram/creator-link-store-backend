package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.creatorstore.bootstrap.DemoDataInitializer;
import dev.creatorstore.service.CreatorProfileService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "instagram.autodm-worker-enabled=false"
})
@AutoConfigureMockMvc
@Sql("/auth-integration-schema.sql")
class CreatorProfileIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate database;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

  @Test
  void authenticatedCreatorUpdatesOnlyTheirProfileAndNormalizationPersists() throws Exception {
    Registered owner = register("owner_profile", "Owner", "owner@example.test");
    Registered other = register("other_profile", "Other", "other@example.test");
    database.update("update creators set avatar_url=? where id=?",
        "https://cdn.example.test/owner.png", owner.id());

    mvc.perform(patch("/api/v1/settings/profile")
            .cookie(owner.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "creatorId": %d,
                  "handle": " Updated_Owner ",
                  "displayName": " Updated Owner ",
                  "bio": " Practical creator systems. ",
                  "phone": " +91 98765 43210 "
                }
                """.formatted(other.id())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(owner.id()))
        .andExpect(jsonPath("$.username").value("updated_owner"))
        .andExpect(jsonPath("$.display_name").value("Updated Owner"))
        .andExpect(jsonPath("$.bio").value("Practical creator systems."))
        .andExpect(jsonPath("$.phone").value("+91 98765 43210"));

    assertThat(profile(owner.id()))
        .containsEntry("handle", "updated_owner")
        .containsEntry("display_name", "Updated Owner")
        .containsEntry("bio", "Practical creator systems.")
        .containsEntry("phone", "+91 98765 43210");
    assertThat(profile(other.id()))
        .containsEntry("handle", "other_profile")
        .containsEntry("display_name", "Other");

    mvc.perform(get("/api/auth/me").cookie(owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handle").value("updated_owner"))
        .andExpect(jsonPath("$.displayName").value("Updated Owner"))
        .andExpect(jsonPath("$.bio").value("Practical creator systems."))
        .andExpect(jsonPath("$.phone").value("+91 98765 43210"))
        .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example.test/owner.png"));

    mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handleOrEmail":"owner@example.test","password":"strong-test-password"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handle").value("updated_owner"))
        .andExpect(jsonPath("$.displayName").value("Updated Owner"))
        .andExpect(jsonPath("$.bio").value("Practical creator systems."))
        .andExpect(jsonPath("$.avatarUrl").value("https://cdn.example.test/owner.png"));

    mvc.perform(get("/api/public/updated_owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.creator.handle").value("updated_owner"))
        .andExpect(jsonPath("$.creator.display_name").value("Updated Owner"))
        .andExpect(jsonPath("$.creator.bio").value("Practical creator systems."))
        .andExpect(jsonPath("$.creator.avatar_url")
            .value("https://cdn.example.test/owner.png"));
    mvc.perform(get("/api/public/owner_profile"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unchangedOwnHandleIsAllowedAndSubmittedPhoneIsPreserved() throws Exception {
    Registered owner = register(
        "same_handle", "Same Handle", "same@example.test", "+1 312 555 0100");

    mvc.perform(patch("/api/v1/settings/profile")
            .cookie(owner.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":" Same_Handle ","displayName":"Same Handle","bio":"Updated bio",
                 "phone":"+1 312 555 0100"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("same_handle"))
        .andExpect(jsonPath("$.phone").value("+1 312 555 0100"));

    assertThat(profile(owner.id()))
        .containsEntry("handle", "same_handle")
        .containsEntry("phone", "+1 312 555 0100");
  }

  @Test
  void profileUpdateRequiresAnAuthenticatedSession() throws Exception {
    Registered owner = register("private_profile", "Private", "private@example.test");

    mvc.perform(patch("/api/v1/settings/profile")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"stolen_profile","displayName":"Stolen","bio":"","phone":null}
                """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Authentication required."));

    assertThat(profile(owner.id())).containsEntry("handle", "private_profile");
  }

  @Test
  void profileUpdateReturnsStableValidationAndCollisionErrorsWithoutWriting() throws Exception {
    Registered owner = register("stable_owner", "Stable Owner", "stable@example.test");
    register("claimed_handle", "Claimed", "claimed@example.test");

    updateExpectingError(owner.cookie(), "x", "Stable Owner", "", null, 400,
        CreatorProfileService.INVALID_HANDLE);
    updateExpectingError(owner.cookie(), " Dashboard ", "Stable Owner", "", null, 400,
        CreatorProfileService.RESERVED_HANDLE);
    updateExpectingError(owner.cookie(), "stable_owner", " ", "", null, 400,
        CreatorProfileService.INVALID_PROFILE);
    updateExpectingError(owner.cookie(), "stable_owner", "Stable Owner", "", "not-a-phone",
        400, CreatorProfileService.INVALID_PROFILE);
    updateExpectingError(owner.cookie(), "claimed_handle", "Stable Owner", "", null, 409,
        CreatorProfileService.HANDLE_CONFLICT);

    assertThat(profile(owner.id()))
        .containsEntry("handle", "stable_owner")
        .containsEntry("display_name", "Stable Owner")
        .containsEntry("bio", "")
        .containsEntry("phone", null);
  }

  private Registered register(String handle, String displayName, String email) throws Exception {
    return register(handle, displayName, email, null);
  }

  private Registered register(String handle, String displayName, String email, String phone)
      throws Exception {
    String phoneValue = phone == null ? "null" : "\"" + phone + "\"";
    MvcResult result = mvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"%s","displayName":"%s","email":"%s","phone":%s,
                 "password":"strong-test-password"}
                """.formatted(handle, displayName, email, phoneValue)))
        .andExpect(status().isCreated())
        .andReturn();
    Cookie cookie = result.getResponse().getCookie("cs_session");
    assertThat(cookie).isNotNull();
    long id = database.queryForObject(
        "select id from creators where handle=?", Long.class, handle);
    return new Registered(id, cookie);
  }

  private void updateExpectingError(Cookie cookie, String handle, String displayName,
                                    String bio, String phone, int statusCode,
                                    String message) throws Exception {
    String phoneValue = phone == null ? "null" : "\"" + phone + "\"";
    mvc.perform(patch("/api/v1/settings/profile")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"%s","displayName":"%s","bio":"%s","phone":%s}
                """.formatted(handle, displayName, bio, phoneValue)))
        .andExpect(status().is(statusCode))
        .andExpect(jsonPath("$.error").value(message));
  }

  private Map<String, Object> profile(long id) {
    return database.queryForMap(
        "select handle,display_name,bio,phone from creators where id=?", id);
  }

  private record Registered(long id, Cookie cookie) {}
}
