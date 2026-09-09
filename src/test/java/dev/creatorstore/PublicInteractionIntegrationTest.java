package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.creatorstore.bootstrap.DemoDataInitializer;
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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:public-interactions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=never",
    "instagram.autodm-worker-enabled=false"
})
@AutoConfigureMockMvc
@Sql("/public-interaction-schema.sql")
class PublicInteractionIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate database;

  @MockitoBean private DemoDataInitializer demoDataInitializer;

  @Test
  void capturesAConfiguredLeadIdempotentlyWithoutReturningPrivateDeliveryData() throws Exception {
    String validBody = """
        {
          "creatorId":10,
          "email":" Visitor@Example.com ",
          "name":" Visitor Name ",
          "phone":" +91 98765 43210 ",
          "consent":true
        }
        """;

    mvc.perform(post("/api/public/products/100/leads")
            .header("Idempotency-Key", "capture-key-0001")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBody))
        .andExpect(status().isAccepted())
        .andExpect(content().json("{\"accepted\":true}"))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("private.example"))));

    Map<String, Object> lead = database.queryForMap(
        "select creator_id as \"creator_id\",product_id as \"product_id\","
            + "email as \"email\",name as \"name\",phone as \"phone\","
            + "consent_given as \"consent_given\",consent_text as \"consent_text\","
            + "request_fingerprint as \"request_fingerprint\" from leads");
    assertThat(lead).containsEntry("creator_id", 10L)
        .containsEntry("product_id", 100L)
        .containsEntry("email", "visitor@example.com")
        .containsEntry("name", "Visitor Name")
        .containsEntry("phone", "+91 98765 43210")
        .containsEntry("consent_given", true)
        .containsEntry("consent_text", "Send me this guide.");
    assertThat(String.valueOf(lead.get("request_fingerprint"))).hasSize(64);

    mvc.perform(post("/api/public/products/100/leads")
            .header("Idempotency-Key", "capture-key-0001")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBody))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true));
    assertThat(database.queryForObject("select count(*) from leads", Integer.class)).isEqualTo(1);

    mvc.perform(post("/api/public/products/100/leads")
            .header("Idempotency-Key", "capture-key-0001")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBody.replace("Visitor@Example.com", "different@example.com")))
        .andExpect(status().isConflict());
    assertThat(database.queryForObject("select count(*) from leads", Integer.class)).isEqualTo(1);
  }

  @Test
  void rejectsMissingPolicyFieldsAndCrossCreatorOrNonLeadProducts() throws Exception {
    mvc.perform(post("/api/public/products/100/leads")
            .header("Idempotency-Key", "capture-key-0002")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"creatorId":10,"email":"visitor@example.com","name":"Visitor"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("A valid phone is required."));

    mvc.perform(post("/api/public/products/100/leads")
            .header("Idempotency-Key", "capture-key-0003")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"creatorId":10,"email":"visitor@example.com","name":"Visitor",
                 "phone":"+91 98765 43210","consent":false}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Consent is required for this resource."));

    mvc.perform(post("/api/public/products/100/leads")
            .header("Idempotency-Key", "capture-key-0004")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"creatorId":20,"email":"visitor@example.com","name":"Visitor",
                 "phone":"+91 98765 43210","consent":true}
                """))
        .andExpect(status().isNotFound());

    mvc.perform(post("/api/public/products/101/leads")
            .header("Idempotency-Key", "capture-key-0005")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"creatorId":10,"email":"visitor@example.com"}
                """))
        .andExpect(status().isNotFound());
    assertThat(database.queryForObject("select count(*) from leads", Integer.class)).isZero();
  }

  @Test
  void resolvesStorefrontViewsServerSideAndRejectsCrossCreatorPayloads() throws Exception {
    mvc.perform(post("/api/events/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"LEAD_CREATOR","path":"/lead_creator",
                 "referrer":"https://instagram.com/example"}
                """))
        .andExpect(status().isAccepted())
        .andExpect(content().json("{\"accepted\":true}"));

    Map<String, Object> visit = database.queryForMap(
        "select creator_id as \"creator_id\",path as \"path\",referrer as \"referrer\" "
            + "from store_visits");
    assertThat(visit).containsEntry("creator_id", 10L)
        .containsEntry("path", "/lead_creator")
        .containsEntry("referrer", "https://instagram.com/example");

    mvc.perform(post("/api/events/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"lead_creator","path":"/other_creator"}
                """))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"event":"page_view","creator_id":20,"path":"/lead_creator"}
                """))
        .andExpect(status().isNotFound());

    mvc.perform(post("/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"event":"page_view","creator_id":10,"path":"/lead_creator"}
                """))
        .andExpect(status().isAccepted());
    assertThat(database.queryForObject(
        "select count(*) from store_visits where creator_id=10", Integer.class)).isEqualTo(2);
    assertThat(database.queryForObject(
        "select count(*) from store_visits where creator_id=20", Integer.class)).isZero();
  }
}
