package dev.creatorstore.service;

import dev.creatorstore.dto.ClickEventRequest;
import dev.creatorstore.repository.EventRepository;
import dev.creatorstore.repository.PromotionRepository;
import dev.creatorstore.support.Values;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EventService {
  private final EventRepository events;
  private final PromotionRepository promotions;

  public EventService(EventRepository events, PromotionRepository promotions) {
    this.events = events;
    this.promotions = promotions;
  }

  public void recordAnalytics(Map<String, Object> event) {
    long creatorId = Values.longValue(event.getOrDefault("creator_id", 1));
    String name = Values.text(event.getOrDefault("event", "page_view"));
    if (name.equals("page_view")) {
      events.recordPageView(creatorId, Values.text(event.getOrDefault("path", "/")),
          Values.text(event.get("referrer")));
    }
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
}
