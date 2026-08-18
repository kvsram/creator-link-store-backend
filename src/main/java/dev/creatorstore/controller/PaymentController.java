package dev.creatorstore.controller;

import dev.creatorstore.dto.CheckoutRequest;
import dev.creatorstore.dto.RazorpayReturnRequest;
import dev.creatorstore.service.PaymentService;
import dev.creatorstore.support.HttpResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @GetMapping("/api/v1/payments/config")
  public Map<String, Object> configuration() {
    return payments.configuration();
  }

  @PostMapping("/api/v1/checkout/sessions")
  public ResponseEntity<?> createCheckout(
      @RequestHeader(value = "Idempotency-Key", defaultValue = "") String idempotencyKey,
      @RequestBody CheckoutRequest request) {
    return response(payments.createCheckout(idempotencyKey, request));
  }

  @PostMapping("/api/v1/payments/razorpay/verify")
  public ResponseEntity<?> verifyRazorpay(@RequestBody RazorpayReturnRequest request) {
    return response(payments.verifyRazorpayReturn(request));
  }

  private static ResponseEntity<?> response(HttpResult result) {
    return ResponseEntity.status(result.status()).body(result.body());
  }
}
