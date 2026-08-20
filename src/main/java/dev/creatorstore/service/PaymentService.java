package dev.creatorstore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.dto.CheckoutRequest;
import dev.creatorstore.dto.RazorpayReturnRequest;
import dev.creatorstore.integration.PaymentProviderClient;
import dev.creatorstore.integration.PaymentProviderCommand;
import dev.creatorstore.integration.ProviderSession;
import dev.creatorstore.integration.RazorpayClient;
import dev.creatorstore.repository.CheckoutRepository;
import dev.creatorstore.repository.ProductRepository;
import dev.creatorstore.security.Signatures;
import dev.creatorstore.support.HttpResult;
import dev.creatorstore.support.Values;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
  private final ProductRepository products;
  private final CheckoutRepository checkouts;
  private final Map<String, PaymentProviderClient> providerClients;
  private final RazorpayClient razorpay;
  private final ObjectMapper json;
  private final String paymentMode;
  private final String defaultProvider;
  private final String razorpayKeySecret;

  public PaymentService(ProductRepository products, CheckoutRepository checkouts,
      List<PaymentProviderClient> providerClients, RazorpayClient razorpay, ObjectMapper json,
      @Value("${payments.mode:disabled}") String paymentMode,
      @Value("${payments.default-provider:razorpay}") String defaultProvider,
      @Value("${payments.razorpay.key-secret:}") String razorpayKeySecret) {
    this.products = products;
    this.checkouts = checkouts;
    this.providerClients = providerClients.stream().collect(
        Collectors.toUnmodifiableMap(PaymentProviderClient::provider, Function.identity()));
    this.razorpay = razorpay;
    this.json = json;
    this.paymentMode = paymentMode;
    this.defaultProvider = defaultProvider;
    this.razorpayKeySecret = razorpayKeySecret;
  }

  public Map<String, Object> configuration() {
    return Map.of("real_money", true, "mode", paymentMode,
        "default_provider", defaultProvider, "default_currency", "INR", "smallest_unit", "paise",
        "providers", List.of(provider("razorpay", "Razorpay", true),
            provider("stripe", "Stripe", false)),
        "notice", "External real-money integration. Disabled mode never creates or confirms a charge.");
  }

  public HttpResult createCheckout(String idempotencyKey, CheckoutRequest input) {
    if (!Set.of("test", "live").contains(paymentMode)) {
      return HttpResult.externalUnavailable("Payments are disabled; no charge was attempted.");
    }
    if (idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
      return HttpResult.error(400, "Idempotency-Key is required and must be at most 120 characters.");
    }
    String buyerEmail = input.buyerEmail() == null ? "" : input.buyerEmail().trim().toLowerCase(Locale.ROOT);
    if (!buyerEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      return HttpResult.error(400, "A valid buyerEmail is required.");
    }
    String buyerName = Values.optional(input.buyerName(), "").trim();
    if (buyerName.length() > 120) return HttpResult.error(400, "buyerName must be at most 120 characters.");
    List<Map<String, Object>> existing = checkouts.findByIdempotencyKey(input.creatorId(), idempotencyKey);
    if (!existing.isEmpty()) return HttpResult.ok(existing.get(0));
    List<Map<String, Object>> rows = products.findCheckoutProduct(input.productId(), input.creatorId());
    if (rows.isEmpty()) return HttpResult.error(404, "Published product not found for this creator.");
    Map<String, Object> product = rows.get(0);
    String currency = String.valueOf(product.get("currency")).trim().toUpperCase(Locale.ROOT);
    if (!currency.equals("INR")) return HttpResult.error(400, "India launch checkout currently requires INR.");
    int amount = ((Number) product.get("amount_subunits")).intValue();
    if (input.planId() != null) {
      List<Map<String, Object>> plans = checkouts.findPlan(input.planId(), input.productId());
      if (plans.isEmpty()) return HttpResult.error(400, "Plan not found for this product.");
      amount = ((Number) plans.get(0).get("amount_subunits")).intValue();
    }
    if (input.slotId() != null && !checkouts.isOpenSlot(input.slotId(), input.productId())) {
      return HttpResult.error(400, "Meeting slot is unavailable or does not belong to this product.");
    }
    Map<String, String> fieldResponses = input.fieldResponses() == null ? Map.of() : input.fieldResponses();
    HttpResult fieldError = validateCheckoutFields(input.productId(), fieldResponses);
    if (fieldError != null) return fieldError;
    if (amount <= 0) return HttpResult.error(400, "Free products do not use a payment gateway.");
    String provider = Values.optional(input.provider(), defaultProvider).toLowerCase(Locale.ROOT);
    PaymentProviderClient client = providerClients.get(provider);
    if (client == null) return HttpResult.error(400, "provider must be razorpay or stripe");
    if (!client.configured()) {
      return HttpResult.externalUnavailable(capitalize(provider)
          + " test/live credentials are not configured; no charge was attempted.");
    }

    String checkoutId = UUID.randomUUID().toString();
    try {
      checkouts.create(checkoutId, input.creatorId(), input.productId(), provider,
          idempotencyKey, currency, amount, buyerEmail, buyerName,
          serialize(fieldResponses), input.slotId(), input.planId());
    } catch (DataIntegrityViolationException concurrentRequest) {
      return HttpResult.ok(checkouts.findOneByIdempotencyKey(input.creatorId(), idempotencyKey));
    }
    try {
      ProviderSession remote = client.createSession(new PaymentProviderCommand(checkoutId,
          String.valueOf(product.get("title")), amount, currency, idempotencyKey));
      checkouts.providerCreated(checkoutId, remote.id());
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("checkout_id", checkoutId);
      response.put("provider", provider);
      response.put("mode", paymentMode);
      response.put("currency", currency);
      response.put("amount_subunits", amount);
      response.put("provider_session_id", remote.id());
      if (provider.equals("razorpay")) {
        response.put("public_key_id", razorpay.publicKeyId());
        response.put("checkout_script", "https://checkout.razorpay.com/v1/checkout.js");
      } else {
        response.put("redirect_url", remote.redirectUrl());
      }
      return HttpResult.created(response);
    } catch (Exception providerFailure) {
      if (providerFailure instanceof InterruptedException) Thread.currentThread().interrupt();
      checkouts.providerFailed(checkoutId);
      return HttpResult.error(502,
          "Payment provider request failed; no local paid order was created.");
    }
  }

  private HttpResult validateCheckoutFields(long productId, Map<String, String> responses) {
    List<Map<String, Object>> fields = checkouts.checkoutFields(productId);
    Set<String> allowed = fields.stream().map(field -> String.valueOf(field.get("id")))
        .collect(Collectors.toSet());
    if (!allowed.containsAll(responses.keySet())) {
      return HttpResult.error(400, "fieldResponses contains a field that does not belong to this product.");
    }
    for (Map<String, Object> field : fields) {
      if (Boolean.TRUE.equals(field.get("required"))) {
        String value = responses.get(String.valueOf(field.get("id")));
        if (value == null || value.isBlank()) {
          return HttpResult.error(400, "Required checkout field is missing: " + field.get("label"));
        }
      }
    }
    if (responses.values().stream().anyMatch(value -> value != null && value.length() > 2000)) {
      return HttpResult.error(400, "Checkout field responses must be at most 2000 characters.");
    }
    return null;
  }

  private String serialize(Map<String, String> value) {
    if (value.isEmpty()) return null;
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException impossibleForStringMap) {
      throw new IllegalArgumentException("Invalid checkout field responses.", impossibleForStringMap);
    }
  }

  public HttpResult verifyRazorpayReturn(RazorpayReturnRequest input) {
    if (!razorpay.configured() || input.orderId() == null || input.paymentId() == null
        || input.signature() == null) {
      return new HttpResult(401, Map.of("verified", false));
    }
    boolean verified = Signatures.verifyHexHmac(razorpayKeySecret,
        input.orderId() + "|" + input.paymentId(), input.signature());
    if (!verified) return new HttpResult(401, Map.of("verified", false));
    checkouts.browserVerified(input.orderId());
    return HttpResult.ok(Map.of("verified", true, "final_status_source", "verified_webhook"));
  }

  private Map<String, Object> provider(String id, String label, boolean indiaPrimary) {
    PaymentProviderClient client = providerClients.get(id);
    return Map.of("id", id, "label", label, "india_primary", indiaPrimary,
        "configured", client != null && client.configured());
  }

  private static String capitalize(String value) {
    return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
  }
}
