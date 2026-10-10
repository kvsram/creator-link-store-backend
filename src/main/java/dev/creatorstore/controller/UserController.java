package dev.creatorstore.controller;

import dev.creatorstore.identity.AuthenticatedCreator;
import dev.creatorstore.service.CreatorProfileService;
import dev.creatorstore.service.UserExperienceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
  private final CreatorProfileService profiles;
  private final UserExperienceService experience;

  public UserController(CreatorProfileService profiles, UserExperienceService experience) {
    this.profiles = profiles;
    this.experience = experience;
  }

  @GetMapping("/api/v1/users/get_user")
  public Map<String, Object> getUser(HttpServletRequest request) {
    return profiles.get(AuthenticatedCreator.id(request));
  }

  @PostMapping("/api/v1/users/experiments/join_communities")
  public Map<String, Object> joinCommunities() {
    return experience.joinCommunity();
  }

  @GetMapping("/api/v1/users/experiments/community_stats")
  public Map<String, Object> communityStats() {
    return experience.communityStats();
  }

  @GetMapping("/api/v1/users/experiments/metadata")
  public Map<String, Object> experimentMetadata() {
    return experience.experimentMetadata();
  }
}
