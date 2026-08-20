package dev.creatorstore.controller;

import dev.creatorstore.service.ContentService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
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
  public Map<String, Object> settings(HttpServletRequest request) {
    return content.settings(AuthenticatedCreator.id(request));
  }
}
