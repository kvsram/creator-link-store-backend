package dev.creatorstore.service;

import dev.creatorstore.dto.ClickEventRequest;
import dev.creatorstore.dto.StoreViewRequest;
import dev.creatorstore.repository.EventRepository;
import dev.creatorstore.repository.PromotionRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventService {
  private static final Pattern HANDLE = Pattern.compile("[a-zA-Z0-9_]{3,40}");
  private final EventRepository events;
  private final PromotionRepository promotions;

  public EventService(EventRepository events, PromotionRepository promotions) {
    this.events = events;
    this.promotions = promotions;
  }

  public void recordLegacyAnalytics(Map<String, Object> event) {
    if (event == null || !"page_view".equals(text(event.getOrDefault("event", "page_view")))) {
      throw badRequest("Only page_view events are accepted here.");
    }
    String handle = text(event.get("handle"));
    String path = text(event.get("path"));
    if (handle.isBlank()) handle = handleFromPath(path);
    Long creatorId = optionalLong(event.containsKey("creator_id")
        ? event.get("creator_id") : event.get("creatorId"));
    recordResolvedView(handle, creatorId, path, text(event.get("referrer")));
  }

  public void recordView(StoreViewRequest request) {
    if (request == null) throw badRequest("A public handle is required.");
    recordResolvedView(request.handle(), null, request.path(), request.referrer());
  }

  private void recordResolvedView(String requestedHandle, Long suppliedCreatorId, String path,
      String referrer) {
    String handle = text(requestedHandle).toLowerCase(Locale.ROOT);
    if (suppliedCreatorId != null && suppliedCreatorId <= 0) throw badRequest("creatorId is invalid.");
    if (handle.isBlank() && suppliedCreatorId != null) {
      List<Map<String, Object>> byId = events.findPublishedCreatorById(suppliedCreatorId);
      if (byId.isEmpty()) throw notFound();
      handle = String.valueOf(byId.get(0).get("handle"));
    }
    if (!HANDLE.matcher(handle).matches()) throw badRequest("handle is invalid.");

    List<Map<String, Object>> creators = events.findPublishedCreatorByHandle(handle);
    if (creators.isEmpty()) throw notFound();
    Map<String, Object> creator = creators.get(0);
    long resolvedCreatorId = ((Number) creator.get("id")).longValue();
    if (suppliedCreatorId != null && suppliedCreatorId != resolvedCreatorId) throw notFound();
    String resolvedHandle = String.valueOf(creator.get("handle"));
    String canonicalPath = "/" + resolvedHandle;
    String cleanPath = text(path);
    if (!cleanPath.isBlank() && !cleanPath.equalsIgnoreCase(canonicalPath)
        && !cleanPath.equalsIgnoreCase(canonicalPath + "/")) {
      throw badRequest("path does not belong to this storefront.");
    }
    events.recordPageView(resolvedCreatorId, canonicalPath, safeMetadata(referrer, 512));
  }

  public void recordClick(ClickEventRequest request, String userAgent) {
    if (!promotions.isTrackable(request.creatorId(), request.linkId()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "published promotion not found");
    events.recordClick(request.linkId(), request.creatorId(), limited(request.path(), 512),
        limited(request.referrer(), 500), limited(userAgent, 500), limited(request.campaign(), 160));
  }

  private static String limited(String value, int maximum) {
    if (value == null) return null;
    String clean = value.trim();
    return clean.substring(0, Math.min(clean.length(), maximum));
  }

  private static String handleFromPath(String path) {
    if (path == null) return "";
    String clean = path.trim();
    if (!clean.startsWith("/") || clean.indexOf('/', 1) >= 0) return "";
    return clean.substring(1);
  }

  private static String safeMetadata(String value, int maximum) {
    String clean = limited(value, maximum);
    if (clean == null || clean.isBlank()) return null;
    if (clean.chars().anyMatch(character -> Character.isISOControl(character))) {
      throw badRequest("referrer contains unsupported characters.");
    }
    return clean;
  }

  private static Long optionalLong(Object value) {
    if (value == null || text(value).isBlank()) return null;
    try {
      return value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
    } catch (NumberFormatException invalid) {
      throw badRequest("creatorId is invalid.");
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "published storefront not found");
  }
}
