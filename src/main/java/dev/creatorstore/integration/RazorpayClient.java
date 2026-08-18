package dev.creatorstore.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RazorpayClient implements PaymentProviderClient {
  private final HttpClient http;
  private final ObjectMapper json;
  private final String keyId;
  private final String keySecret;

  public RazorpayClient(HttpClient http, ObjectMapper json,
      @Value("${payments.razorpay.key-id:}") String keyId,
      @Value("${payments.razorpay.key-secret:}") String keySecret) {
    this.http = http;
    this.json = json;
    this.keyId = keyId;
    this.keySecret = keySecret;
  }

  @Override
  public String provider() {
    return "razorpay";
  }

  @Override
  public boolean configured() {
    return !keyId.isBlank() && !keySecret.isBlank();
  }

  public String publicKeyId() {
    return keyId;
  }

  @Override
  public ProviderSession createSession(PaymentProviderCommand command) throws Exception {
    String body = json.writeValueAsString(Map.of("amount", command.amountSubunits(),
        "currency", command.currency(), "receipt", command.checkoutId()));
    String basic = Base64.getEncoder().encodeToString(
        (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/orders"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + basic)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("razorpay status " + response.statusCode());
    }
    return new ProviderSession(json.readTree(response.body()).path("id").asText(), null);
  }
}
