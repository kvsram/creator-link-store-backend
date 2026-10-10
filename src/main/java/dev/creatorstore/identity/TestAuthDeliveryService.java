package dev.creatorstore.identity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicitly gated view of the durable auth-email outbox for automated test environments.
 * Production startup rejects this mode.
 */
@Service
public class TestAuthDeliveryService {
  private final boolean enabled;
  private final String accessKey;
  private final SecurityFingerprintService fingerprints;
  private final AuthDeliveryRepository deliveries;

  public TestAuthDeliveryService(
      @Value("${app.auth.test-delivery-enabled:false}") boolean enabled,
      @Value("${app.auth.test-delivery-key:}") String accessKey,
      SecurityFingerprintService fingerprints, AuthDeliveryRepository deliveries) {
    this.enabled = enabled && accessKey != null && !accessKey.isBlank();
    this.accessKey = accessKey;
    this.fingerprints = fingerprints;
    this.deliveries = deliveries;
  }

  public Delivery retrieve(UUID id, String suppliedKey) {
    if (!enabled || !fingerprints.constantTimeEquals(accessKey, suppliedKey)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Test delivery not found.");
    }
    AuthDeliveryRepository.Delivery delivery = deliveries.findForTest(id)
        .filter(value -> value.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Test delivery not found."));
    return new Delivery(delivery.deliveryId(), delivery.type(), delivery.recipient(),
        delivery.token(), delivery.expiresAt());
  }

  public boolean enabled() {
    return enabled;
  }

  public record Delivery(UUID deliveryId, String type, String recipient, String token,
                         OffsetDateTime expiresAt) {}
}
