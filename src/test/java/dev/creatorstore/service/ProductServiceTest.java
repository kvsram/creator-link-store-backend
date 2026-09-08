package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.creatorstore.domain.ProductTypeConfiguration;
import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;
import dev.creatorstore.dto.ProductRequest;
import dev.creatorstore.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProductServiceTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductConfigurationService configurations = mock(ProductConfigurationService.class);
  private final ProductService service = new ProductService(products, configurations);

  @Test
  void createsProductAndConfigurationAsOneAggregate() {
    ProductRequest request = request("digital-download", "draft", Map.of("deliveryMode", "upload"));
    Delivery configuration = new Delivery(1, "upload", "", false, true, false, "");
    when(configurations.subtitle("Subtitle")).thenReturn("Subtitle");
    when(configurations.callToAction("Download")).thenReturn("Download");
    when(configurations.thumbnailStyle("preview")).thenReturn("preview");
    when(configurations.validate("digital-download", request.configuration())).thenReturn(configuration);
    when(configurations.encode(configuration)).thenReturn("{\"schemaVersion\":1}");
    when(products.create(eq(1L), eq("digital-download"), eq("Product"), eq("Description"),
        eq(1000), eq("draft"), eq(1), eq("Subtitle"), eq("Download"), eq("preview"),
        anyString(), eq(null))).thenReturn(Map.of("id", 9L, "_created", true));
    when(configurations.synchronizeAndStore(1, 9, "digital-download", configuration,
        "Subtitle", "Download", "preview")).thenReturn(configuration);
    when(configurations.creatorDetails(1, 9)).thenReturn(Map.of("id", 9L));

    service.create(1, request);

    verify(configurations).synchronizeAndStore(1, 9, "digital-download", configuration,
        "Subtitle", "Download", "preview");
    verify(configurations, never()).ensurePublishReady(anyInt(), anyInt(), anyString(),
        any(ProductTypeConfiguration.class), anyInt());
  }

  @Test
  void refusesChangingProductType() {
    when(products.findOwned(1, 9)).thenReturn(List.of(Map.of(
        "id", 9L, "type", "meeting", "status", "draft", "configuration_json", "{}")));

    assertThatThrownBy(() -> service.update(1, 9,
        request("course", "draft", Map.of("modules", List.of()))))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

    verify(products, never()).update(anyInt(), anyInt(), anyString(), anyString(), anyInt(),
        anyString(), anyInt(), anyString(), anyString(), anyString());
  }

  @Test
  void leadMagnetMustRemainFreeEvenAsDraft() {
    ProductRequest invalid = new ProductRequest(0, "lead-magnet", "Free guide", "Description",
        100, "draft", 1, "", "Get", "preview", Map.of("deliveryMode", "upload"));

    assertThatThrownBy(() -> service.create(1, invalid))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("must be free");
  }

  @Test
  void returnsExistingAggregateWhenCreateKeyWasAlreadyCommitted() {
    ProductRequest request = request("digital-download", "draft", Map.of("deliveryMode", "upload"));
    Delivery configuration = new Delivery(1, "upload", "", false, true, false, "");
    when(configurations.subtitle("Subtitle")).thenReturn("Subtitle");
    when(configurations.callToAction("Download")).thenReturn("Download");
    when(configurations.thumbnailStyle("preview")).thenReturn("preview");
    when(configurations.validate("digital-download", request.configuration())).thenReturn(configuration);
    when(configurations.encode(configuration)).thenReturn("{\"schemaVersion\":1}");
    when(products.create(eq(1L), eq("digital-download"), eq("Product"), eq("Description"),
        eq(1000), eq("draft"), eq(1), anyString(), anyString(), anyString(), anyString(),
        eq("same-key"))).thenReturn(Map.of("id", 9L, "_created", false));
    when(configurations.creatorDetails(1, 9)).thenReturn(Map.of("id", 9L));

    service.create(1, "same-key", request);

    verify(configurations, never()).synchronizeAndStore(anyInt(), anyInt(), anyString(),
        any(ProductTypeConfiguration.class), anyString(), anyString(), anyString());
  }

  private static ProductRequest request(String type, String status,
      Map<String, Object> configuration) {
    return new ProductRequest(0, type, "Product", "Description", 1000, status, 1,
        "Subtitle", "Download", "preview", configuration);
  }
}
