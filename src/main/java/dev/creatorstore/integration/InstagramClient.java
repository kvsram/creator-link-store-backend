package dev.creatorstore.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InstagramClient {
  private final HttpClient http;
  private final ObjectMapper json;
  private final String apiVersion;
  private final String accountId;
  private final String accessToken;

  public InstagramClient(HttpClient http, ObjectMapper json,
      @Value("${instagram.graph-api-version:v23.0}") String apiVersion,
      @Value("${instagram.account-id:}") String accountId,
      @Value("${instagram.access-token:}") String accessToken) {
    this.http = http;
    this.json = json;
    this.apiVersion = apiVersion;
    this.accountId = accountId;
    this.accessToken = accessToken;
  }

  public boolean configured(String appSecret) {
    return !accountId.isBlank() && !accessToken.isBlank() && !appSecret.isBlank();
  }

  public Map<String, Object> send(String recipientId, String text) throws Exception {
    String body = json.writeValueAsString(
        Map.of("recipient", Map.of("id", recipientId), "message", Map.of("text", text)));
    HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://graph.instagram.com/" + apiVersion + "/" + accountId + "/messages"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + accessToken)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new ProviderRejectedException(response.statusCode());
    }
    JsonNode result = json.readTree(response.body());
    return Map.of("sent", true, "recipient_id", result.path("recipient_id").asText(),
        "message_id", result.path("message_id").asText(), "external_service", "instagram");
  }

  public static class ProviderRejectedException extends Exception {
    private final int statusCode;

    public ProviderRejectedException(int statusCode) {
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }
}
