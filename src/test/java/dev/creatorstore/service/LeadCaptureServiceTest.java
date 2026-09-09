package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.creatorstore.dto.LeadCaptureRequest;
import dev.creatorstore.repository.LeadRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class LeadCaptureServiceTest {
  @Test
  void concurrentIdenticalIdempotencyWinnerIsReturnedAsSuccess() {
    LeadRepository leads = mock(LeadRepository.class);
    ProductConfigurationService configurations = mock(ProductConfigurationService.class);
    ProductConfigurationValidator validator = new ProductConfigurationValidator();
    LeadCaptureService service = new LeadCaptureService(leads, configurations, validator);
    AtomicReference<String> winnerFingerprint = new AtomicReference<>();

    when(leads.findPublishedLeadMagnet(100L)).thenReturn(List.of(Map.of(
        "id", 100L, "creator_id", 10L, "configuration_json", "{}")));
    when(configurations.parseSnapshot("{}")).thenReturn(Map.of(
        "collectName", false, "collectEmail", true, "collectPhone", false));
    when(leads.findByIdempotencyKey(100L, "concurrent-key-1")).thenAnswer(invocation ->
        winnerFingerprint.get() == null ? List.of()
            : List.of(Map.of("request_fingerprint", winnerFingerprint.get())));
    doAnswer(invocation -> {
      winnerFingerprint.set(invocation.getArgument(8));
      throw new DataIntegrityViolationException("simulated concurrent unique-key winner");
    }).when(leads).create(anyLong(), anyLong(), anyString(), isNull(), isNull(),
        anyBoolean(), isNull(), anyString(), anyString());

    assertThatCode(() -> service.capture(100L, "concurrent-key-1",
        new LeadCaptureRequest(10L, "visitor@example.com", null, null, false)))
        .doesNotThrowAnyException();
  }
}
