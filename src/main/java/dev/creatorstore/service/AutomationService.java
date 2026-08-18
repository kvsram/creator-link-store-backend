package dev.creatorstore.service;

import dev.creatorstore.repository.AutomationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AutomationService {
  private final AutomationRepository automations;

  public AutomationService(AutomationRepository automations) {
    this.automations = automations;
  }

  public Map<String, Object> instagramMetadata(long creatorId) {
    return Map.of("connected", false, "posts", List.of(), "creator_id", creatorId);
  }

  public Map<String, Object> analytics(List<Long> ids) {
    if (ids == null || ids.isEmpty()) return Map.of("items", List.of());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Long id : ids) {
      List<Map<String, Object>> rows = automations.findStats(id);
      items.add(rows.isEmpty()
          ? Map.of("automation_id", id, "comments_seen", 0, "messages_sent", 0, "link_clicks", 0)
          : rows.get(0));
    }
    return Map.of("items", items);
  }
}
