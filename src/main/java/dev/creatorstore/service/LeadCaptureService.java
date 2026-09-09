package dev.creatorstore.service;

import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;
import dev.creatorstore.dto.LeadCaptureRequest;
import dev.creatorstore.repository.LeadRepository;
import dev.creatorstore.security.Signatures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadCaptureService {
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern PHONE = Pattern.compile("^[+()0-9 .-]{7,32}$");
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,120}");

  private final LeadRepository leads;
  private final ProductConfigurationService configurations;
  private final ProductConfigurationValidator validator;

  public LeadCaptureService(LeadRepository leads, ProductConfigurationService configurations,
      ProductConfigurationValidator validator) {
    this.leads = leads;
    this.configurations = configurations;
    this.validator = validator;
  }

  public void capture(long productId, String idempotencyKey, LeadCaptureRequest request) {
    String key = clean(idempotencyKey);
    if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw badRequest("A valid Idempotency-Key header is required.");
    }
    if (request == null) throw badRequest("Lead details are required.");

    List<Map<String, Object>> products = leads.findPublishedLeadMagnet(productId);
    if (products.isEmpty()) throw notFound();
    Map<String, Object> product = products.get(0);
    long creatorId = ((Number) product.get("creator_id")).longValue();
    if (request.creatorId() != null && request.creatorId() != creatorId) throw notFound();

    Delivery policy;
    try {
      policy = (Delivery) validator.validate("lead-magnet",
          configurations.parseSnapshot(product.get("configuration_json")));
    } catch (RuntimeException invalidStoredConfiguration) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Lead capture is temporarily unavailable.");
    }

    String email = clean(request.email()).toLowerCase(Locale.ROOT);
    if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
      throw badRequest("A valid email is required.");
    }
    String name = optional(request.name(), 120, "name");
    if (policy.collectName() && name == null) throw badRequest("Name is required.");
    if (!policy.collectName()) name = null;

    String phone = optional(request.phone(), 32, "phone");
    if (policy.collectPhone() && (phone == null || !PHONE.matcher(phone).matches())) {
      throw badRequest("A valid phone is required.");
    }
    if (!policy.collectPhone()) phone = null;

    String consentText = clean(policy.consentText());
    boolean consentGiven = Boolean.TRUE.equals(request.consent());
    if (!consentText.isBlank() && !consentGiven) {
      throw badRequest("Consent is required for this resource.");
    }
    boolean recordedConsent = !consentText.isBlank() && consentGiven;

    String fingerprint = fingerprint(creatorId, productId, email, name, phone, recordedConsent,
        consentText);
    List<Map<String, Object>> existing = leads.findByIdempotencyKey(productId, key);
    if (!existing.isEmpty()) {
      if (!fingerprint.equals(String.valueOf(existing.get(0).get("request_fingerprint")))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "That Idempotency-Key was already used for another request.");
      }
      return;
    }
    try {
      leads.create(creatorId, productId, email, name, phone, recordedConsent,
          consentText.isBlank() ? null : consentText, key, fingerprint);
    } catch (DataIntegrityViolationException concurrentReplay) {
      List<Map<String, Object>> winner = leads.findByIdempotencyKey(productId, key);
      if (!winner.isEmpty()
          && fingerprint.equals(String.valueOf(winner.get(0).get("request_fingerprint")))) {
        return;
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "That Idempotency-Key was already used for another request.");
    }
  }

  private static String optional(String value, int maximum, String field) {
    String clean = clean(value);
    if (clean.isBlank()) return null;
    if (clean.length() > maximum) throw badRequest(field + " is too long.");
    return clean;
  }

  private static String fingerprint(long creatorId, long productId, String email, String name,
      String phone, boolean consent, String consentText) {
    String canonical = creatorId + "\n" + productId + "\n" + email + "\n"
        + String.valueOf(name) + "\n" + String.valueOf(phone) + "\n" + consent + "\n"
        + consentText;
    return Signatures.sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "published lead magnet not found");
  }
}
