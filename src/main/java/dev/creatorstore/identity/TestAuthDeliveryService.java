package dev.creatorstore.identity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicitly gated in-memory mailbox for public test deployments without an email provider.
 * It is disabled by default, requires a separate key, and never persists raw auth tokens.
 */
@Service
public class TestAuthDeliveryService {
  private final boolean enabled;
  private final String accessKey;
  private final SecurityFingerprintService fingerprints;
  private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();

  public TestAuthDeliveryService(
      @Value("${app.auth.test-delivery-enabled:false}") boolean enabled,
      @Value("${app.auth.test-delivery-key:}") String accessKey,
      SecurityFingerprintService fingerprints) {
    this.enabled = enabled && accessKey != null && !accessKey.isBlank();
    this.accessKey = accessKey;
    this.fingerprints = fingerprints;
  }

  public Optional<UUID> deliver(String type, String recipient, String token,
                                OffsetDateTime expiresAt) {
    if (!enabled) return Optional.empty();
    removeExpired();
    UUID id = UUID.randomUUID();
    deliveries.put(id, new Delivery(id, type, recipient, token, expiresAt));
    return Optional.of(id);
  }

  public Delivery retrieve(UUID id, String suppliedKey) {
    if (!enabled || !fingerprints.constantTimeEquals(accessKey, suppliedKey)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Test delivery not found.");
    }
    Delivery delivery = deliveries.get(id);
    if (delivery == null || !delivery.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
      deliveries.remove(id);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Test delivery not found.");
    }
    return delivery;
  }

  public boolean enabled() {
    return enabled;
  }

  private void removeExpired() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    deliveries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
  }

  public record Delivery(UUID deliveryId, String type, String recipient, String token,
                         OffsetDateTime expiresAt) {}
}
