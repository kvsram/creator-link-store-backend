package dev.creatorstore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.dto.ProductConfigurationRequest;
import dev.creatorstore.repository.ProductRepository;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductConfigurationService {
  private final ProductRepository products;
  private final ObjectMapper json;

  public ProductConfigurationService(ProductRepository products, ObjectMapper json) {
    this.products = products;
    this.json = json;
  }

  public Map<String, Object> update(long creatorId, long productId,
      ProductConfigurationRequest request) {
    var owned = products.findOwned(creatorId, productId);
    if (owned.isEmpty()) throw error(HttpStatus.NOT_FOUND, "product not found");
    String type = text(owned.get(0).get("type"));
    Map<String, Object> config = request.configuration() == null ? Map.of() : request.configuration();
    validate(type, config);
    String subtitle = limited(request.subtitle(), 160, "subtitle");
    String cta = limited(request.callToAction(), 60, "call to action");
    if (cta.isBlank()) cta = "Get access";
    String style = text(request.thumbnailStyle());
    if (!List.of("button", "callout", "preview").contains(style))
      throw error(HttpStatus.BAD_REQUEST, "thumbnailStyle must be button, callout, or preview");
    try {
      String encoded = json.writeValueAsString(config);
      if (encoded.length() > 50_000) throw error(HttpStatus.BAD_REQUEST, "product configuration is too large");
      return products.updateConfiguration(creatorId, productId, subtitle, cta, style, encoded);
    } catch (JsonProcessingException exception) {
      throw error(HttpStatus.BAD_REQUEST, "product configuration is not valid JSON");
    }
  }

  private void validate(String type, Map<String, Object> config) {
    switch (type) {
      case "digital-download", "lead-magnet" -> {
        String mode = text(config.get("deliveryMode"));
        if (!List.of("upload", "redirect").contains(mode))
          throw error(HttpStatus.BAD_REQUEST, "deliveryMode must be upload or redirect");
        if ("redirect".equals(mode)) requireHttps(text(config.get("redirectUrl")), "redirect URL");
      }
      case "meeting" -> {
        required(config, "timezone", "meeting timezone");
        boundedNumber(config, "durationMinutes", 15, 480);
        boundedNumber(config, "maxAttendees", 1, 500);
      }
      case "webinar" -> {
        required(config, "startsAt", "webinar start time");
        boundedNumber(config, "durationMinutes", 15, 480);
        boundedNumber(config, "capacity", 1, 100_000);
      }
      case "course" -> {
        Object modules = config.get("modules");
        if (!(modules instanceof List<?>)) throw error(HttpStatus.BAD_REQUEST, "course modules must be a list");
      }
      case "membership" -> {
        if (!List.of("daily", "weekly", "monthly", "annual").contains(text(config.get("billingInterval"))))
          throw error(HttpStatus.BAD_REQUEST, "unsupported membership billing interval");
      }
      case "fulfillment" -> boundedNumber(config, "turnaroundDays", 1, 365);
      case "community" -> required(config, "memberBenefits", "community member benefits");
      default -> { }
    }
  }

  private static void required(Map<String, Object> config, String key, String label) {
    if (text(config.get(key)).isBlank()) throw error(HttpStatus.BAD_REQUEST, label + " is required");
  }

  private static void boundedNumber(Map<String, Object> config, String key, int min, int max) {
    Object raw = config.get(key);
    int value;
    try { value = raw instanceof Number n ? n.intValue() : Integer.parseInt(text(raw)); }
    catch (NumberFormatException exception) { throw error(HttpStatus.BAD_REQUEST, key + " must be a number"); }
    if (value < min || value > max) throw error(HttpStatus.BAD_REQUEST, key + " is outside the supported range");
  }

  private static void requireHttps(String value, String label) {
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) { throw error(HttpStatus.BAD_REQUEST, label + " must be an absolute HTTPS URL"); }
  }

  private static String limited(String value, int max, String label) {
    String clean = value == null ? "" : value.trim();
    if (clean.length() > max) throw error(HttpStatus.BAD_REQUEST, label + " is too long");
    return clean;
  }
  private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
  private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
