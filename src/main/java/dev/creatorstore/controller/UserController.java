package dev.creatorstore.controller;

import dev.creatorstore.service.CreatorService;
import dev.creatorstore.service.UserExperienceService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
  private final CreatorService creators;
  private final UserExperienceService experience;

  public UserController(CreatorService creators, UserExperienceService experience) {
    this.creators = creators;
    this.experience = experience;
  }

  @GetMapping("/api/v1/users/get_user")
  public Map<String, Object> getUser(@RequestParam(defaultValue = "alex") String handle) {
    return creators.user(handle);
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
