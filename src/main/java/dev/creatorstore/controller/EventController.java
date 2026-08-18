package dev.creatorstore.controller;

import dev.creatorstore.dto.ClickEventRequest;
import dev.creatorstore.service.EventService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {
  private final EventService events;

  public EventController(EventService events) {
    this.events = events;
  }

  @PostMapping("/events")
  public ResponseEntity<Map<String, Object>> analytics(@RequestBody Map<String, Object> event) {
    events.recordAnalytics(event);
    return ResponseEntity.accepted().body(Map.of("accepted", true));
  }

  @PostMapping("/api/events/click")
  public ResponseEntity<Void> click(@RequestBody ClickEventRequest request) {
    events.recordClick(request);
    return ResponseEntity.accepted().build();
  }
}
