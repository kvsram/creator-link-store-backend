package dev.creatorstore.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Retryable worker for transactional verification and password-reset email outbox rows. */
@Service
public class AuthDeliveryWorker {
  private final AuthDeliveryRepository deliveries;
  private final AuthDeliveryService policy;
  private final AuthEmailSender sender;
  private final boolean enabled;

  public AuthDeliveryWorker(AuthDeliveryRepository deliveries, AuthDeliveryService policy,
                            AuthEmailSender sender,
      @Value("${app.auth.delivery-worker-enabled:true}") boolean enabled) {
    this.deliveries = deliveries;
    this.policy = policy;
    this.sender = sender;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${app.auth.delivery-poll-ms:1000}")
  public void drain() {
    if (!enabled || !policy.smtpEnabled()) return;
    deliveries.expire();
    for (int processed = 0; processed < 20; processed++) {
      var claimed = deliveries.claim();
      if (claimed.isEmpty()) return;
      var delivery = claimed.get();
      try {
        sender.send(delivery);
        deliveries.markSent(delivery);
      } catch (RuntimeException failure) {
        deliveries.markFailed(delivery, failure.getClass().getSimpleName() + ": "
            + failure.getMessage());
      }
    }
  }
}
