package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.creatorstore.dto.PromotionRequest;
import dev.creatorstore.repository.PromotionRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PromotionServiceTest {
  private final PromotionService service = new PromotionService((PromotionRepository) null);

  @Test
  void rejectsUnsafeDestinationSchemes() {
    PromotionRequest request = new PromotionRequest("Offer", "javascript:alert(1)", "", "", null,
        "Shop", "", "", "", 0, true, null, null);
    assertThatThrownBy(() -> service.create(1, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("absolute HTTPS URL");
  }

  @Test
  void rejectsInvalidSchedule() {
    var now = java.time.OffsetDateTime.now();
    PromotionRequest request = new PromotionRequest("Offer", "https://example.com/deal", "", "", null,
        "Shop", "", "", "", 0, true, now, now.minusMinutes(1));
    assertThatThrownBy(() -> service.create(1, request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("end time");
  }

  @Test
  void pinsOwnedPromotion() {
    PromotionRepository repository = mock(PromotionRepository.class);
    when(repository.setPinned(7, 12, true)).thenReturn(java.util.List.of(
        java.util.Map.of("id", 12L, "pinned", true)));

    Map<String, Object> result = new PromotionService(repository).setPinned(7, 12, true);

    assertThat(result.get("pinned")).isEqualTo(true);
  }
}
