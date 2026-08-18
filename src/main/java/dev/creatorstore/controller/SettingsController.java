package dev.creatorstore.controller;

import dev.creatorstore.service.ContentService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {
  private final ContentService content;

  public SettingsController(ContentService content) {
    this.content = content;
  }

  @GetMapping("/api/v1/settings")
  public Map<String, Object> settings(@RequestParam(defaultValue = "1") long creatorId) {
    return content.settings(creatorId);
  }
}
