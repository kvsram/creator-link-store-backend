package dev.creatorstore.controller;

import dev.creatorstore.dto.LeadCaptureRequest;
import dev.creatorstore.service.LeadCaptureService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicLeadController {
  private final LeadCaptureService leads;

  public PublicLeadController(LeadCaptureService leads) {
    this.leads = leads;
  }

  @PostMapping("/api/public/products/{productId}/leads")
  public ResponseEntity<Map<String, Object>> capture(
      @PathVariable long productId,
      @RequestHeader(value = "Idempotency-Key", defaultValue = "") String idempotencyKey,
      @RequestBody LeadCaptureRequest request) {
    leads.capture(productId, idempotencyKey, request);
    return ResponseEntity.accepted().body(Map.of("accepted", true));
  }
}
