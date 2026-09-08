package dev.creatorstore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.domain.ProductTypeConfiguration;
import dev.creatorstore.domain.ProductTypeConfiguration.Community;
import dev.creatorstore.domain.ProductTypeConfiguration.Course;
import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;
import dev.creatorstore.domain.ProductTypeConfiguration.Fulfillment;
import dev.creatorstore.domain.ProductTypeConfiguration.Meeting;
import dev.creatorstore.domain.ProductTypeConfiguration.Membership;
import dev.creatorstore.domain.ProductTypeConfiguration.Webinar;
import dev.creatorstore.dto.ProductConfigurationRequest;
import dev.creatorstore.repository.ProductConfigurationRepository;
import dev.creatorstore.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creator aggregate configuration and deliberately-safe public projections. */
@Service
public class ProductConfigurationService {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ProductRepository products;
  private final ProductConfigurationRepository operational;
  private final ProductConfigurationValidator validator;
  private final ObjectMapper json;

  public ProductConfigurationService(ProductRepository products,
      ProductConfigurationRepository operational, ProductConfigurationValidator validator,
      ObjectMapper json) {
    this.products = products;
    this.operational = operational;
    this.validator = validator;
    this.json = json;
  }

  /** Backward-compatible endpoint used by the original two-step editor. */
  @Transactional
  public Map<String, Object> update(long creatorId, long productId,
      ProductConfigurationRequest request) {
    Map<String, Object> product = owned(creatorId, productId);
    String type = text(product.get("type"));
    ProductTypeConfiguration canonical = validator.validate(type, request.configuration());
    canonical = operational.synchronize(creatorId, productId, canonical);
    String subtitle = subtitle(request.subtitle());
    String callToAction = callToAction(request.callToAction());
    String thumbnailStyle = thumbnailStyle(request.thumbnailStyle());
    products.updateConfiguration(creatorId, productId, subtitle, callToAction, thumbnailStyle,
        encode(canonical));
    if ("published".equals(text(product.get("status"))))
      ensurePublishReady(creatorId, productId, type, canonical,
          ((Number) product.get("price_subunits")).intValue());
    return creatorDetails(creatorId, productId);
  }

  public ProductTypeConfiguration validate(String type, Map<String, Object> configuration) {
    return validator.validate(type, configuration);
  }

  /** Synchronizes normalized tables and saves a canonical snapshot containing generated ids. */
  public ProductTypeConfiguration synchronizeAndStore(long creatorId, long productId, String type,
      ProductTypeConfiguration configuration, String subtitle, String callToAction,
      String thumbnailStyle) {
    ProductTypeConfiguration saved = operational.synchronize(creatorId, productId, configuration);
    products.updateConfiguration(creatorId, productId, subtitle, callToAction, thumbnailStyle,
        encode(saved));
    return saved;
  }

  public String encode(ProductTypeConfiguration configuration) {
    try {
      String encoded = json.writeValueAsString(validator.editorSnapshot(configuration));
      if (encoded.length() > 50_000) throw badRequest("product configuration is too large");
      return encoded;
    } catch (JsonProcessingException exception) {
      throw badRequest("product configuration is not valid JSON");
    }
  }

  public Map<String, Object> parseSnapshot(Object encoded) {
    if (encoded == null || String.valueOf(encoded).isBlank()) return Map.of();
    try { return json.readValue(String.valueOf(encoded), MAP_TYPE); }
    catch (JsonProcessingException exception) { throw badRequest("stored product configuration is invalid"); }
  }

  public String subtitle(String value) { return limited(value, 160, "subtitle"); }

  public String callToAction(String value) {
    String clean = limited(value, 60, "callToAction");
    return clean.isBlank() ? "Get access" : clean;
  }

  public String thumbnailStyle(String value) {
    String clean = value == null || value.isBlank() ? "preview" : value.trim();
    if (!List.of("button", "callout", "preview").contains(clean))
      throw badRequest("thumbnailStyle must be button, callout, or preview");
    return clean;
  }

  public void ensurePublishReady(long creatorId, long productId, String type,
      ProductTypeConfiguration configuration, int priceSubunits) {
    if ("lead-magnet".equals(type)) {
      if (priceSubunits != 0) throw badRequest("lead magnets must be free");
    } else if (priceSubunits <= 0) {
      throw badRequest("published paid products require a positive priceSubunits value");
    }
    if (configuration instanceof Delivery delivery) {
      if ("upload".equals(delivery.deliveryMode())
          && products.countFiles(creatorId, productId, "download") == 0)
        throw conflict("upload a delivery file before publishing");
      if ("redirect".equals(delivery.deliveryMode()) && delivery.redirectUrl().isBlank())
        throw conflict("add a secure redirect URL before publishing");
    } else if (configuration instanceof Meeting) {
      if (operational.futureMeetingSlotCount(productId) == 0)
        throw conflict("add at least one future meeting slot before publishing");
    } else if (configuration instanceof Webinar webinar) {
      if (webinar.sessions().stream().anyMatch(session -> session.joinUrl().isBlank())
          || operational.futureWebinarSessionCount(productId) == 0)
        throw conflict("add a future webinar session with a private HTTPS join URL before publishing");
    } else if (configuration instanceof Course course) {
      if (course.modules().isEmpty()
          || course.modules().stream().anyMatch(module -> module.title().isBlank()
              || module.lessons().isEmpty()
              || module.lessons().stream().anyMatch(lesson -> lesson.title().isBlank()))
          || operational.courseLessonCount(productId) == 0)
        throw conflict("add titled modules and at least one titled lesson to every module before publishing");
    } else if (configuration instanceof Membership membership) {
      if (membership.benefits().isEmpty()
          || membership.plans().stream().anyMatch(plan -> plan.name().isBlank()
              || plan.amountSubunits() <= 0)
          || operational.membershipPlanCount(productId) == 0)
        throw conflict("add membership benefits and a paid plan before publishing");
    } else if (configuration instanceof Fulfillment fulfillment) {
      if (fulfillment.turnaroundDays() < 1
          || fulfillment.checkoutFields().stream().anyMatch(field -> field.label().isBlank()))
        throw conflict("configure fulfillment before publishing");
    } else if (configuration instanceof Community community) {
      if (community.accessUrl().isBlank() || community.benefits().isEmpty())
        throw conflict("add the private community access URL and at least one benefit before publishing");
    }
  }

  public Map<String, Object> creatorDetails(long creatorId, long productId) {
    Map<String, Object> product = owned(creatorId, productId);
    Map<String, Object> response = new LinkedHashMap<>(product);
    response.put("configuration", parseSnapshot(product.get("configuration_json")));
    response.put("files", products.files(creatorId, productId));
    addOperational(response, productId, text(product.get("type")), true);
    return response;
  }

  public Map<String, Object> publicDetails(String handle, long productId) {
    List<Map<String, Object>> rows = products.findPublic(handle, productId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    return publicProduct(rows.get(0));
  }

  /** Adds a safe configuration summary to a product already authorized by the public-store query. */
  public Map<String, Object> publicProduct(Map<String, Object> row) {
    Map<String, Object> response = new LinkedHashMap<>(row);
    long productId = ((Number) row.get("id")).longValue();
    String type = text(row.get("type"));
    Object encoded = response.remove("configuration_json");
    response.put("public_configuration", safeProjection(productId, type, parseSnapshot(encoded)));
    return response;
  }

  private Map<String, Object> safeProjection(long productId, String type,
      Map<String, Object> snapshot) {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("schemaVersion", ProductConfigurationValidator.SCHEMA_VERSION);
    ProductTypeConfiguration configuration;
    try {
      configuration = validator.validate(type, snapshot);
    } catch (ResponseStatusException legacyOrInvalidConfiguration) {
      // A malformed legacy snapshot must not take down the creator's entire public storefront.
      safe.put("configurationState", "unavailable");
      return safe;
    }
    if (configuration instanceof Delivery delivery) {
      safe.put("delivery", "lead-magnet".equals(type) ? "lead-capture" : "after-purchase");
      if ("lead-magnet".equals(type)) {
        safe.put("collectName", delivery.collectName());
        safe.put("collectEmail", true);
        safe.put("collectPhone", delivery.collectPhone());
        safe.put("consentText", delivery.consentText());
      }
    } else if (configuration instanceof Meeting meeting) {
      safe.put("location", meeting.location());
      safe.put("timezone", meeting.timezone());
      safe.put("durationMinutes", meeting.durationMinutes());
      safe.put("maxAttendees", meeting.maxAttendees());
      safe.put("minNoticeHours", meeting.minNoticeHours());
      safe.put("slots", operational.meetingSlots(productId, true));
    } else if (configuration instanceof Webinar webinar) {
      safe.put("location", webinar.location());
      safe.put("timezone", webinar.timezone());
      safe.put("durationMinutes", webinar.durationMinutes());
      safe.put("sessions", operational.webinarSessions(productId, false));
    } else if (configuration instanceof Course course) {
      safe.put("dripDays", course.dripDays());
      safe.put("modules", operational.courseModules(productId, false));
    } else if (configuration instanceof Membership membership) {
      safe.put("benefits", membership.benefits());
      safe.put("plans", operational.paymentPlans(productId));
    } else if (configuration instanceof Fulfillment fulfillment) {
      safe.put("turnaroundDays", fulfillment.turnaroundDays());
      safe.put("deliveryFormat", fulfillment.deliveryFormat());
      safe.put("checkoutFields", operational.checkoutFields(productId));
    } else if (configuration instanceof Community community) {
      safe.put("platform", community.platform());
      safe.put("benefits", community.benefits());
    }
    return safe;
  }

  private void addOperational(Map<String, Object> response, long productId, String type,
      boolean includePrivate) {
    response.put("meeting_slots", "meeting".equals(type)
        ? operational.meetingSlots(productId, false) : List.of());
    response.put("webinar_sessions", "webinar".equals(type)
        ? operational.webinarSessions(productId, includePrivate) : List.of());
    response.put("payment_plans", "membership".equals(type)
        ? operational.paymentPlans(productId) : List.of());
    response.put("checkout_fields", "fulfillment".equals(type)
        ? operational.checkoutFields(productId) : List.of());
    response.put("course_modules", "course".equals(type)
        ? operational.courseModules(productId, includePrivate) : List.of());
  }

  private Map<String, Object> owned(long creatorId, long productId) {
    List<Map<String, Object>> rows = products.findOwnedDetails(creatorId, productId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    return rows.get(0);
  }

  private static String limited(String value, int max, String label) {
    String clean = value == null ? "" : value.trim();
    if (clean.length() > max) throw badRequest(label + " is too long");
    return clean;
  }

  private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
