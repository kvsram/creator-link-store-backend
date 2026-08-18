package dev.creatorstore.controller;

import dev.creatorstore.service.PaymentWebhookService;
import dev.creatorstore.support.HttpResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentWebhookController {
  private final PaymentWebhookService webhooks;

  public PaymentWebhookController(PaymentWebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping("/api/v1/webhooks/razorpay")
  public ResponseEntity<?> razorpay(
      @RequestHeader(value = "X-Razorpay-Signature", defaultValue = "") String signature,
      @RequestBody byte[] raw) {
    return response(webhooks.razorpay(signature, raw));
  }

  @PostMapping("/api/v1/webhooks/stripe")
  public ResponseEntity<?> stripe(
      @RequestHeader(value = "Stripe-Signature", defaultValue = "") String signature,
      @RequestBody byte[] raw) {
    return response(webhooks.stripe(signature, raw));
  }

  private static ResponseEntity<?> response(HttpResult result) {
    return ResponseEntity.status(result.status()).body(result.body());
  }
}
