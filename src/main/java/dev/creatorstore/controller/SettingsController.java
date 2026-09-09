package dev.creatorstore.controller;

import dev.creatorstore.service.ContentService;
import dev.creatorstore.service.CreatorProfileService;
import dev.creatorstore.dto.ProfileUpdateRequest;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {
  private final ContentService content;
  private final CreatorProfileService profiles;

  public SettingsController(ContentService content, CreatorProfileService profiles) {
    this.content = content;
    this.profiles = profiles;
  }

  @GetMapping("/api/v1/settings")
  public Map<String, Object> settings(HttpServletRequest request) {
    return content.settings(AuthenticatedCreator.id(request));
  }

  @PatchMapping("/api/v1/settings/profile")
  public Map<String, Object> updateProfile(@RequestBody ProfileUpdateRequest body,
                                            HttpServletRequest request) {
    return profiles.update(AuthenticatedCreator.id(request), body);
  }
}
