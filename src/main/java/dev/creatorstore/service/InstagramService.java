package dev.creatorstore.service;

import dev.creatorstore.dto.InstagramMessageRequest;
import dev.creatorstore.integration.InstagramClient;
import dev.creatorstore.repository.WebhookRepository;
import dev.creatorstore.security.Signatures;
import dev.creatorstore.support.HttpResult;
import dev.creatorstore.support.Values;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class InstagramService {
  private final InstagramClient instagram;
  private final WebhookRepository webhooks;
  private final String mode;
  private final String appSecret;
  private final String verifyToken;
  private final String recipientIds;

  public InstagramService(InstagramClient instagram, WebhookRepository webhooks,
      @Value("${instagram.mode:disabled}") String mode,
      @Value("${instagram.app-secret:}") String appSecret,
      @Value("${instagram.verify-token:}") String verifyToken,
      @Value("${instagram.test-recipient-ids:}") String recipientIds) {
    this.instagram = instagram;
    this.webhooks = webhooks;
    this.mode = mode;
    this.appSecret = appSecret;
    this.verifyToken = verifyToken;
    this.recipientIds = recipientIds;
  }

  public Map<String, Object> configuration() {
    return Map.of("external_service", true, "mode", mode, "configured", configured(),
        "send_enabled", Set.of("test", "live").contains(mode) && configured(),
        "test_recipient_count", testRecipients().size(),
        "required_permission", "instagram_business_manage_messages",
        "notice", "In test mode, messages can only be sent to allowlisted recipients who first messaged the professional account.");
  }

  public HttpResult verifyWebhook(String webhookMode, String token, String challenge) {
    if ("subscribe".equals(webhookMode) && !verifyToken.isBlank()
        && MessageDigest.isEqual(verifyToken.getBytes(StandardCharsets.UTF_8),
            Values.optional(token, "").getBytes(StandardCharsets.UTF_8))) {
      return HttpResult.ok(Values.optional(challenge, ""));
    }
    return new HttpResult(403, "verification failed");
  }

  public HttpResult receiveWebhook(String signature, byte[] raw) {
    String provided = signature.startsWith("sha256=") ? signature.substring(7) : "";
    if (appSecret.isBlank() || !Signatures.verifyHexHmac(appSecret, raw, provided)) {
      return HttpResult.error(401, "invalid signature");
    }
    String hash = Signatures.sha256(raw);
    try {
      webhooks.recordInstagramEvent(hash);
    } catch (DataIntegrityViolationException duplicate) {
      return HttpResult.ok(Map.of("received", true, "duplicate", true));
    }
    return HttpResult.ok(Map.of("received", true));
  }

  public HttpResult sendTest(boolean confirmed, InstagramMessageRequest input) {
    if (!confirmed) return HttpResult.error(428, "X-Confirm-External-Send: true is required.");
    if (!Set.of("test", "live").contains(mode) || !configured()) {
      return HttpResult.externalUnavailable("Instagram messaging is disabled or not configured.");
    }
    if (input.recipientId() == null || input.text() == null || input.text().isBlank()
        || input.text().length() > 1000) {
      return HttpResult.error(400, "recipientId and a 1-1000 character text are required.");
    }
    if (mode.equals("test") && !testRecipients().contains(input.recipientId())) {
      return HttpResult.error(403, "Recipient is not in INSTAGRAM_TEST_RECIPIENT_IDS.");
    }
    try {
      return HttpResult.ok(instagram.send(input.recipientId(), input.text()));
    } catch (InstagramClient.ProviderRejectedException rejected) {
      return new HttpResult(502, Map.of("error", "Instagram rejected the test send.",
          "provider_status", rejected.statusCode()));
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return HttpResult.error(502, "Instagram request failed.");
    }
  }

  private boolean configured() {
    return instagram.configured(appSecret);
  }

  private Set<String> testRecipients() {
    Set<String> recipients = new HashSet<>();
    for (String recipient : recipientIds.split(",")) {
      if (!recipient.isBlank()) recipients.add(recipient.trim());
    }
    return recipients;
  }
}
