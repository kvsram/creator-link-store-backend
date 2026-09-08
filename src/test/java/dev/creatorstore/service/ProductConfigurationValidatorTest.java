package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.creatorstore.domain.ProductTypeConfiguration.Community;
import dev.creatorstore.domain.ProductTypeConfiguration.Course;
import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;
import dev.creatorstore.domain.ProductTypeConfiguration.Fulfillment;
import dev.creatorstore.domain.ProductTypeConfiguration.Meeting;
import dev.creatorstore.domain.ProductTypeConfiguration.Membership;
import dev.creatorstore.domain.ProductTypeConfiguration.Webinar;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProductConfigurationValidatorTest {
  private final ProductConfigurationValidator validator = new ProductConfigurationValidator();

  @Test
  void canonicalizesLeadCapturePolicy() {
    Delivery value = (Delivery) validator.validate("lead-magnet", Map.of(
        "deliveryMode", "upload", "collectName", false, "collectEmail", true,
        "collectPhone", true, "consentText", "Send me this guide"));

    assertThat(value.schemaVersion()).isEqualTo(1);
    assertThat(value.collectName()).isFalse();
    assertThat(value.collectEmail()).isTrue();
    assertThat(value.collectPhone()).isTrue();
  }

  @Test
  void permitsIncompleteRedirectInDraftModelButStillRejectsUnsafeUrl() {
    Delivery draft = (Delivery) validator.validate("digital-download",
        Map.of("deliveryMode", "redirect", "redirectUrl", ""));
    assertThat(draft.redirectUrl()).isEmpty();

    assertThatThrownBy(() -> validator.validate("digital-download",
        Map.of("deliveryMode", "redirect", "redirectUrl", "http://unsafe.example/file")))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("HTTPS");
  }

  @Test
  void reReadsCanonicalDigitalDownloadSnapshot() {
    Delivery original = (Delivery) validator.validate("digital-download",
        Map.of("deliveryMode", "upload"));

    Delivery restored = (Delivery) validator.validate("digital-download",
        validator.editorSnapshot(original));

    assertThat(restored.deliveryMode()).isEqualTo("upload");
    assertThat(restored.redirectUrl()).isEmpty();
  }

  @Test
  void validatesIanaTimezoneAndNonOverlappingMeetingSlots() {
    assertThatThrownBy(() -> validator.validate("meeting", Map.of("timezone", "Mars/Olympus")))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("IANA");

    assertThatThrownBy(() -> validator.validate("meeting", Map.of(
        "timezone", "Asia/Kolkata", "durationMinutes", 60,
        "slots", List.of(
            Map.of("startsAt", "2030-01-01T10:00:00Z", "endsAt", "2030-01-01T11:00:00Z"),
            Map.of("startsAt", "2030-01-01T10:30:00Z", "endsAt", "2030-01-01T11:30:00Z")))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("overlap");
  }

  @Test
  void acceptsPhoneMeetingAndLocationDetails() {
    Meeting value = (Meeting) validator.validate("meeting", Map.of(
        "location", "phone", "locationDetails", "Creator will call", "timezone", "Asia/Kolkata"));
    assertThat(value.location()).isEqualTo("phone");
    assertThat(value.locationDetails()).isEqualTo("Creator will call");
  }

  @Test
  void requiresIsoInstantAndHttpsForWebinarSessionValues() {
    assertThatThrownBy(() -> validator.validate("webinar", Map.of(
        "sessions", List.of(Map.of("startsAt", "2030-01-01 10:00", "endsAt",
            "2030-01-01T11:00:00Z", "joinUrl", "https://meet.example/1")))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ISO-8601");

    Webinar value = (Webinar) validator.validate("webinar", Map.of(
        "timezone", "Asia/Kolkata", "sessions", List.of(Map.of(
            "startsAt", "2030-01-01T10:00:00Z", "endsAt", "2030-01-01T11:00:00Z",
            "joinUrl", "https://meet.example/1", "capacity", 250))));
    assertThat(value.sessions()).hasSize(1);
  }

  @Test
  void validatesNestedCourseAndAllowsIncompleteDraftTitles() {
    Course value = (Course) validator.validate("course", Map.of("modules", List.of(Map.of(
        "title", "", "description", "Draft module", "lessons", List.of(Map.of(
            "title", "", "description", "Draft lesson", "videoUrl", ""))))));
    assertThat(value.modules()).hasSize(1);
    assertThat(value.modules().get(0).description()).isEqualTo("Draft module");

    assertThatThrownBy(() -> validator.validate("course", Map.of("modules", List.of(Map.of(
        "title", "Module", "lessons", List.of(Map.of("title", "Lesson", "videoUrl",
            "javascript:alert(1)")))))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("HTTPS");
  }

  @Test
  void canonicalizesMembershipBenefitsAndPlans() {
    Membership value = (Membership) validator.validate("membership", Map.of(
        "benefits", List.of("Weekly call", "Templates"),
        "plans", List.of(Map.of("name", "Monthly", "amountSubunits", 99900,
            "interval", "monthly", "intervalCount", 1))));

    assertThat(value.benefits()).containsExactly("Weekly call", "Templates");
    assertThat(value.plans().get(0).amountSubunits()).isEqualTo(99900);
  }

  @Test
  void validatesFulfillmentFieldTypes() {
    Fulfillment value = (Fulfillment) validator.validate("fulfillment", Map.of(
        "checkoutFields", List.of(Map.of("label", "Website", "fieldType", "url",
            "required", true))));
    assertThat(value.checkoutFields().get(0).fieldType()).isEqualTo("url");

    assertThatThrownBy(() -> validator.validate("fulfillment", Map.of(
        "checkoutFields", List.of(Map.of("label", "Secret", "fieldType", "password")))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("fieldType");
  }

  @Test
  void validatesPrivateCommunityAccessWithoutExposingItInBenefits() {
    Community value = (Community) validator.validate("community", Map.of(
        "platform", "discord", "accessUrl", "https://discord.example/invite",
        "benefits", List.of("Weekly office hours")));
    assertThat(value.accessUrl()).startsWith("https://");
    assertThat(value.benefits()).containsExactly("Weekly office hours");
  }

  @Test
  void rejectsUnknownFieldsAndFutureSchemaVersions() {
    assertThatThrownBy(() -> validator.validate("community", Map.of("secret", "value")))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("unsupported configuration field");
    assertThatThrownBy(() -> validator.validate("community", Map.of("schemaVersion", 2)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("schemaVersion");
  }
}
