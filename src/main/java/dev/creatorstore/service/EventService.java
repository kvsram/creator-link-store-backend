package dev.creatorstore.service;

import dev.creatorstore.dto.ClickEventRequest;
import dev.creatorstore.repository.EventRepository;
import dev.creatorstore.support.Values;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EventService {
  private final EventRepository events;

  public EventService(EventRepository events) {
    this.events = events;
  }

  public void recordAnalytics(Map<String, Object> event) {
    long creatorId = Values.longValue(event.getOrDefault("creator_id", 1));
    String name = Values.text(event.getOrDefault("event", "page_view"));
    if (name.equals("page_view")) {
      events.recordPageView(creatorId, Values.text(event.getOrDefault("path", "/")),
          Values.text(event.get("referrer")));
    }
  }

  public void recordClick(ClickEventRequest request) {
    events.recordClick(request.linkId(), request.referrer());
  }
}
