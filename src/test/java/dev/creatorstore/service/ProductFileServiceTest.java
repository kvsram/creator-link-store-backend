package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;
import dev.creatorstore.repository.ProductRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ProductFileServiceTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductConfigurationService configurations = mock(ProductConfigurationService.class);

  @Test
  void rejectsFileKindsThatDoNotBelongToProductType(@TempDir Path directory) {
    when(products.findOwned(1, 2)).thenReturn(List.of(Map.of("type", "meeting")));
    ProductFileService service = new ProductFileService(products, configurations,
        directory.toString());

    assertThatThrownBy(() -> service.upload(1, 2, "download",
        new MockMultipartFile("file", "guide.pdf", "application/pdf", new byte[] {1})))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("product type");
    verify(products, never()).addFile(anyLong(), anyString(), anyString(), anyString(),
        anyLong(), anyString());
  }

  @Test
  void permitsCourseLessonVideoKind(@TempDir Path directory) {
    when(products.findOwned(1, 2)).thenReturn(List.of(Map.of("type", "course")));
    when(products.addFile(eq(2L), eq("lesson.mp4"), anyString(), eq("video/mp4"), eq(1L),
        eq("lesson-video"))).thenReturn(Map.of("id", 3L));
    ProductFileService service = new ProductFileService(products, configurations,
        directory.toString());

    service.upload(1, 2, "lesson-video",
        new MockMultipartFile("file", "lesson.mp4", "video/mp4", new byte[] {1}));

    verify(products).addFile(eq(2L), eq("lesson.mp4"), anyString(), eq("video/mp4"), eq(1L),
        eq("lesson-video"));
  }

  @Test
  void protectsOnlyDeliveryFileOfPublishedUpload(@TempDir Path directory) {
    when(products.findOwnedFile(1, 2, 3)).thenReturn(List.of(Map.of(
        "id", 3L, "object_key", "1/2/key", "kind", "download",
        "type", "digital-download", "status", "published")));
    when(products.findOwned(1, 2)).thenReturn(List.of(Map.of(
        "configuration_json", "{\"deliveryMode\":\"upload\"}")));
    Map<String, Object> snapshot = Map.of("deliveryMode", "upload");
    when(configurations.parseSnapshot(any())).thenReturn(snapshot);
    when(configurations.validate("digital-download", snapshot)).thenReturn(
        new Delivery(1, "upload", "", false, true, false, ""));
    when(products.countFiles(1, 2, "download")).thenReturn(1L);
    ProductFileService service = new ProductFileService(products, configurations,
        directory.toString());

    assertThatThrownBy(() -> service.delete(1, 2, 3))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("unpublish");
    verify(products, never()).deleteFile(1, 2, 3);
  }
}
