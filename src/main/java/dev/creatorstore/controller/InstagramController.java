package dev.creatorstore.controller;

import dev.creatorstore.dto.InstagramMessageRequest;
import dev.creatorstore.service.InstagramService;
import dev.creatorstore.support.HttpResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InstagramController {
  private final InstagramService instagram;

  public InstagramController(InstagramService instagram) {
    this.instagram = instagram;
  }

  @GetMapping("/api/v1/integrations/instagram/config")
  public Map<String, Object> configuration() {
    return instagram.configuration();
  }

  @GetMapping("/api/v1/webhooks/instagram")
  public ResponseEntity<?> verifyWebhook(
      @RequestParam(name = "hub.mode", required = false) String mode,
      @RequestParam(name = "hub.verify_token", required = false) String token,
      @RequestParam(name = "hub.challenge", required = false) String challenge) {
    return response(instagram.verifyWebhook(mode, token, challenge));
  }

  @PostMapping("/api/v1/webhooks/instagram")
  public ResponseEntity<?> receiveWebhook(
      @RequestHeader(value = "X-Hub-Signature-256", defaultValue = "") String signature,
      @RequestBody byte[] raw) {
    return response(instagram.receiveWebhook(signature, raw));
  }

  @PostMapping("/api/v1/integrations/instagram/test-message")
  public ResponseEntity<?> sendTest(
      @RequestHeader(value = "X-Confirm-External-Send", defaultValue = "false") boolean confirmed,
      @RequestBody InstagramMessageRequest request) {
    return response(instagram.sendTest(confirmed, request));
  }

  private static ResponseEntity<?> response(HttpResult result) {
    return ResponseEntity.status(result.status()).body(result.body());
  }
}
