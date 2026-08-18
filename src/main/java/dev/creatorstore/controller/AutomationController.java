package dev.creatorstore.controller;

import dev.creatorstore.service.AutomationService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutomationController {
  private final AutomationService automations;

  public AutomationController(AutomationService automations) {
    this.automations = automations;
  }

  @GetMapping("/api/v1/automations/instagram-posts-metadata")
  public Map<String, Object> instagramMetadata(@RequestParam(defaultValue = "1") long creatorId) {
    return automations.instagramMetadata(creatorId);
  }

  @GetMapping("/api/v1/automations/analytics")
  public Map<String, Object> analytics(
      @RequestParam(name = "automation_ids", required = false) List<Long> ids) {
    return automations.analytics(ids);
  }
}
