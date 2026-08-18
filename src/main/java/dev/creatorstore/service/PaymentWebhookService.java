package dev.creatorstore.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.repository.CheckoutRepository;
import dev.creatorstore.repository.WebhookRepository;
import dev.creatorstore.security.Signatures;
import dev.creatorstore.support.HttpResult;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookService {
  private final ObjectMapper json;
  private final CheckoutRepository checkouts;
  private final WebhookRepository webhooks;
  private final String razorpaySecret;
  private final String stripeSecret;

  public PaymentWebhookService(ObjectMapper json, CheckoutRepository checkouts,
      WebhookRepository webhooks,
      @Value("${payments.razorpay.webhook-secret:}") String razorpaySecret,
      @Value("${payments.stripe.webhook-secret:}") String stripeSecret) {
    this.json = json;
    this.checkouts = checkouts;
    this.webhooks = webhooks;
    this.razorpaySecret = razorpaySecret;
    this.stripeSecret = stripeSecret;
  }

  public HttpResult razorpay(String signature, byte[] raw) {
    if (razorpaySecret.isBlank() || !Signatures.verifyHexHmac(razorpaySecret, raw, signature)) {
      return HttpResult.error(401, "invalid signature");
    }
    return record("razorpay", raw);
  }

  public HttpResult stripe(String signature, byte[] raw) {
    if (stripeSecret.isBlank()
        || !Signatures.verifyStripe(stripeSecret, raw, signature, Instant.now().getEpochSecond())) {
      return HttpResult.error(401, "invalid signature");
    }
    return record("stripe", raw);
  }

  private HttpResult record(String provider, byte[] raw) {
    try {
      JsonNode event = json.readTree(raw);
      String type = event.path("event").asText(event.path("type").asText("unknown"));
      String providerSessionId = provider.equals("razorpay")
          ? event.path("payload").path("order").path("entity").path("id")
              .asText(event.path("payload").path("payment").path("entity").path("order_id").asText())
          : event.path("data").path("object").path("id").asText();
      String hash = Signatures.sha256(raw);
      try {
        webhooks.recordPaymentEvent(provider, hash, type);
      } catch (DataIntegrityViolationException duplicate) {
        return HttpResult.ok(Map.of("received", true, "duplicate", true));
      }
      if (!providerSessionId.isBlank()
          && Set.of("order.paid", "payment.captured", "checkout.session.completed").contains(type)) {
        checkouts.paid(provider, providerSessionId);
      }
      return HttpResult.ok(Map.of("received", true));
    } catch (Exception invalidPayload) {
      return HttpResult.error(400, "invalid JSON payload");
    }
  }
}
