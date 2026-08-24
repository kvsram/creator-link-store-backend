package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.dto.ProductConfigurationRequest;
import dev.creatorstore.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProductConfigurationServiceTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductConfigurationService service =
      new ProductConfigurationService(products, new ObjectMapper());

  @Test
  void rejectsUnsafeDownloadRedirect() {
    when(products.findOwned(1, 8)).thenReturn(List.of(Map.of("type", "digital-download")));
    var request = new ProductConfigurationRequest("Guide", "Download", "preview",
        Map.of("deliveryMode", "redirect", "redirectUrl", "javascript:alert(1)"));

    assertThatThrownBy(() -> service.update(1, 8, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("absolute HTTPS URL");
  }

  @Test
  void rejectsMeetingDurationOutsideStanStyleBounds() {
    when(products.findOwned(1, 9)).thenReturn(List.of(Map.of("type", "meeting")));
    var request = new ProductConfigurationRequest("Call", "Book", "callout",
        Map.of("timezone", "America/Chicago", "durationMinutes", 5, "maxAttendees", 1));

    assertThatThrownBy(() -> service.update(1, 9, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("outside the supported range");
  }

  @Test
  void persistsValidatedCourseConfiguration() {
    when(products.findOwned(1, 10)).thenReturn(List.of(Map.of("type", "course")));
    when(products.updateConfiguration(eq(1L), eq(10L), eq("Learn"), eq("Start course"),
        eq("preview"), anyString())).thenReturn(Map.of("id", 10));
    var request = new ProductConfigurationRequest("Learn", "Start course", "preview",
        Map.of("modules", List.of(Map.of("title", "Module 1"))));

    service.update(1, 10, request);

    verify(products).updateConfiguration(eq(1L), eq(10L), eq("Learn"), eq("Start course"),
        eq("preview"), anyString());
  }
}
