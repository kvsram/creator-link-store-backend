package dev.creatorstore.service;

import dev.creatorstore.dto.PromotionRequest;
import dev.creatorstore.repository.PromotionRepository;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PromotionService {
  private final PromotionRepository promotions;

  public PromotionService(PromotionRepository promotions) {
    this.promotions = promotions;
  }

  public Map<String, Object> create(long creatorId, PromotionRequest input) {
    return promotions.create(creatorId, validate(input));
  }

  public Map<String, Object> update(long creatorId, long id, PromotionRequest input) {
    var rows = promotions.update(creatorId, id, validate(input));
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion not found");
    return rows.get(0);
  }

  public void delete(long creatorId, long id) {
    if (promotions.delete(creatorId, id) == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion not found");
  }

  public Map<String, Object> setPinned(long creatorId, long id, boolean pinned) {
    var rows = promotions.setPinned(creatorId, id, pinned);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion not found");
    return rows.get(0);
  }

  private PromotionRequest validate(PromotionRequest input) {
    String title = clean(input.title());
    String url = clean(input.url());
    String description = clean(input.description());
    String brand = clean(input.brandName());
    String thumbnail = nullable(input.thumbnailUrl());
    String cta = clean(input.callToAction());
    String coupon = clean(input.couponCode());
    String offer = clean(input.offerText());
    String disclosure = clean(input.disclosure());
    if (title.isBlank() || title.length() > 100 || description.length() > 500
        || brand.length() > 100 || cta.length() > 60 || coupon.length() > 80
        || offer.length() > 120 || disclosure.length() > 160)
      throw bad("promotion fields exceed their limits or title is missing");
    if (cta.isBlank()) cta = "Visit link";
    requireHttps(url, "destination URL");
    if (thumbnail != null) requireHttps(thumbnail, "thumbnail URL");
    OffsetDateTime starts = input.startsAt();
    OffsetDateTime ends = input.endsAt();
    if (starts != null && ends != null && !ends.isAfter(starts))
      throw bad("promotion end time must be after its start time");
    return new PromotionRequest(title, url, description, brand, thumbnail, cta, coupon,
        offer, disclosure, input.position() == null ? 0 : input.position(),
        Boolean.TRUE.equals(input.published()), starts, ends);
  }

  private static void requireHttps(String value, String label) {
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) {
      throw bad(label + " must be an absolute HTTPS URL");
    }
  }

  private static String clean(String value) { return value == null ? "" : value.trim(); }
  private static String nullable(String value) { String clean = clean(value); return clean.isBlank() ? null : clean; }
  private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
