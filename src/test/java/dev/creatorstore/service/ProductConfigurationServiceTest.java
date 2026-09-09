package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.dto.ProductConfigurationRequest;
import dev.creatorstore.repository.ProductConfigurationRepository;
import dev.creatorstore.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProductConfigurationServiceTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductConfigurationRepository operational = mock(ProductConfigurationRepository.class);
  private final ProductConfigurationValidator validator = new ProductConfigurationValidator();
  private final ProductConfigurationService service =
      new ProductConfigurationService(products, operational, validator, new ObjectMapper());

  @BeforeEach
  void passCanonicalConfigurationThroughOperationalPersistence() {
    when(operational.synchronize(eq(1L), any(Long.class), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
  }

  @Test
  void rejectsUnsafeDownloadRedirect() {
    owned(8, "digital-download", "draft", "{}");
    var request = new ProductConfigurationRequest("Guide", "Download", "preview",
        Map.of("deliveryMode", "redirect", "redirectUrl", "javascript:alert(1)"));

    assertThatThrownBy(() -> service.update(1, 8, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("absolute HTTPS URL");
  }

  @Test
  void rejectsMeetingDurationOutsideBounds() {
    owned(9, "meeting", "draft", "{}");
    var request = new ProductConfigurationRequest("Call", "Book", "callout",
        Map.of("timezone", "America/Chicago", "durationMinutes", 5, "maxAttendees", 1));

    assertThatThrownBy(() -> service.update(1, 9, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("outside the supported range");
  }

  @Test
  void persistsCanonicalCourseConfiguration() {
    owned(10, "course", "draft", "{}");
    when(products.files(1, 10)).thenReturn(List.of());
    when(operational.courseModules(10, true)).thenReturn(List.of());
    var request = new ProductConfigurationRequest("Learn", "Start course", "preview",
        Map.of("modules", List.of(Map.of("title", "Module 1", "description", "Start here",
            "lessons", List.of(Map.of("title", "Lesson 1", "description", "Welcome"))))));

    Map<String, Object> result = service.update(1, 10, request);

    verify(products).updateConfiguration(eq(1L), eq(10L), eq("Learn"),
        eq("Start course"), eq("preview"), anyString());
    assertThat(result).containsKey("configuration");
  }

  @Test
  void publicProjectionNeverLeaksDeliveryRedirect() {
    Map<String, Object> publicRow = Map.of(
        "id", 12L, "type", "digital-download", "title", "Guide",
        "configuration_json", "{\"schemaVersion\":1,\"deliveryMode\":\"redirect\","
            + "\"redirectUrl\":\"https://private.example/secret\"}");

    Map<String, Object> result = service.publicProduct(publicRow);

    assertThat(result).doesNotContainKey("configuration_json");
    assertThat(String.valueOf(result)).doesNotContain("private.example", "redirectUrl");
  }

  @Test
  void publishedLeadMagnetProjectionEnablesCaptureWithoutLeakingDeliveryRedirect() {
    Map<String, Object> publicRow = Map.of(
        "id", 120L, "type", "lead-magnet", "title", "Free guide",
        "configuration_json", "{\"schemaVersion\":1,\"deliveryMode\":\"redirect\","
            + "\"redirectUrl\":\"https://private.example/lead-guide\","
            + "\"collectName\":true,\"collectEmail\":true,\"collectPhone\":false,"
            + "\"consentText\":\"\"}");

    Map<String, Object> result = service.publicProduct(publicRow);

    assertThat(result).containsEntry("lead_capture_enabled", true);
    assertThat(String.valueOf(result)).doesNotContain("private.example", "redirectUrl");
  }

  @Test
  void publicWebinarProjectionUsesSanitizedOperationalSessions() {
    Map<String, Object> publicRow = Map.of(
        "id", 13L, "type", "webinar", "title", "Live class",
        "configuration_json", "{\"schemaVersion\":1,\"timezone\":\"UTC\","
            + "\"sessions\":[{\"startsAt\":\"2030-01-01T10:00:00Z\","
            + "\"endsAt\":\"2030-01-01T11:00:00Z\",\"capacity\":50,"
            + "\"joinUrl\":\"https://private.example/join\"}]}" );
    when(operational.webinarSessions(13, false)).thenReturn(List.of(Map.of(
        "id", 9L, "starts_at", "2030-01-01T10:00:00Z", "capacity", 50)));

    Map<String, Object> result = service.publicProduct(publicRow);

    assertThat(String.valueOf(result)).doesNotContain("joinUrl", "private.example");
    assertThat(String.valueOf(result)).contains("sessions");
  }

  @Test
  void publicCourseProjectionUsesOnlySanitizedOperationalOutline() {
    Map<String, Object> publicRow = Map.of(
        "id", 14L, "type", "course", "title", "Course",
        "configuration_json", "{\"schemaVersion\":1,\"dripDays\":2,"
            + "\"modules\":[{\"title\":\"Plan\",\"description\":\"private module\","
            + "\"lessons\":[{\"title\":\"Lesson\",\"description\":\"private lesson\","
            + "\"videoUrl\":\"https://private.example/video\"}]}]}" );
    when(operational.courseModules(14, false)).thenReturn(List.of(Map.of(
        "id", 3L, "title", "Plan", "position", 0,
        "lessons", List.of(Map.of("id", 4L, "title", "Lesson", "position", 0)))));

    Map<String, Object> result = service.publicProduct(publicRow);

    assertThat(String.valueOf(result)).contains("Plan", "Lesson");
    assertThat(String.valueOf(result)).doesNotContain(
        "private module", "private lesson", "private.example", "videoUrl");
  }

  @Test
  void paidProductCannotPublishWithZeroPrice() {
    assertThatThrownBy(() -> service.ensurePublishReady(1, 15, "course",
        validator.validate("course", Map.of("modules", List.of())), 0))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("positive priceSubunits");
  }

  private void owned(long id, String type, String status, String configuration) {
    when(products.findOwnedDetails(1, id)).thenReturn(List.of(Map.of(
        "id", id, "type", type, "status", status, "price_subunits", 0,
        "configuration_json", configuration)));
  }
}
