package dev.creatorstore.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StripeClient implements PaymentProviderClient {
  private final HttpClient http;
  private final ObjectMapper json;
  private final String secretKey;
  private final String successUrl;
  private final String cancelUrl;

  public StripeClient(HttpClient http, ObjectMapper json,
      @Value("${payments.stripe.secret-key:}") String secretKey,
      @Value("${payments.success-url}") String successUrl,
      @Value("${payments.cancel-url}") String cancelUrl) {
    this.http = http;
    this.json = json;
    this.secretKey = secretKey;
    this.successUrl = successUrl;
    this.cancelUrl = cancelUrl;
  }

  @Override
  public String provider() {
    return "stripe";
  }

  @Override
  public boolean configured() {
    return !secretKey.isBlank();
  }

  @Override
  public ProviderSession createSession(PaymentProviderCommand command) throws Exception {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("mode", "payment");
    fields.put("success_url", successUrl);
    fields.put("cancel_url", cancelUrl);
    fields.put("client_reference_id", command.checkoutId());
    fields.put("line_items[0][quantity]", "1");
    fields.put("line_items[0][price_data][currency]", command.currency().toLowerCase(Locale.ROOT));
    fields.put("line_items[0][price_data][unit_amount]", String.valueOf(command.amountSubunits()));
    fields.put("line_items[0][price_data][product_data][name]", command.title());
    String form = fields.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .reduce((left, right) -> left + "&" + right).orElse("");
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/checkout/sessions"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + secretKey)
        .header("Idempotency-Key", command.idempotencyKey())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("stripe status " + response.statusCode());
    }
    JsonNode body = json.readTree(response.body());
    return new ProviderSession(body.path("id").asText(), body.path("url").asText());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
