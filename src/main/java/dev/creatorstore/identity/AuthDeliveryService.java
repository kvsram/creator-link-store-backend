package dev.creatorstore.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Establishes whether auth email can be delivered and transactionally enqueues it. */
@Service
public class AuthDeliveryService {
  private final AuthDeliveryRepository deliveries;
  private final String mode;
  private final boolean testDeliveryEnabled;
  private final String testDeliveryKey;
  private final String fromAddress;

  public AuthDeliveryService(AuthDeliveryRepository deliveries,
      @Value("${app.auth.email-mode:disabled}") String mode,
      @Value("${app.auth.test-delivery-enabled:false}") boolean testDeliveryEnabled,
      @Value("${app.auth.test-delivery-key:}") String testDeliveryKey,
      @Value("${app.auth.email-from:}") String fromAddress) {
    this.deliveries = deliveries;
    this.mode = mode == null ? "disabled" : mode.trim().toLowerCase();
    this.testDeliveryEnabled = testDeliveryEnabled;
    this.testDeliveryKey = testDeliveryKey;
    this.fromAddress = fromAddress;
  }

  public void requireAvailable() {
    boolean testAvailable = "test".equals(mode) && testDeliveryEnabled
        && testDeliveryKey != null && !testDeliveryKey.isBlank();
    boolean smtpAvailable = "smtp".equals(mode) && fromAddress != null
        && !fromAddress.isBlank();
    if (!testAvailable && !smtpAvailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Account email delivery is temporarily unavailable.");
    }
  }

  public UUID enqueue(Long accountId, String type, String recipient, String token,
                      OffsetDateTime expiresAt) {
    return deliveries.enqueue(accountId, type, recipient, token, expiresAt);
  }

  public int supersede(Long accountId, String type) {
    return deliveries.supersede(accountId, type);
  }

  public Optional<UUID> exposedTestDelivery(UUID deliveryId) {
    return "test".equals(mode) && testDeliveryEnabled && testDeliveryKey != null
        && !testDeliveryKey.isBlank() ? Optional.ofNullable(deliveryId) : Optional.empty();
  }

  public boolean smtpEnabled() {
    return "smtp".equals(mode);
  }

  public String mode() {
    return mode;
  }
}
