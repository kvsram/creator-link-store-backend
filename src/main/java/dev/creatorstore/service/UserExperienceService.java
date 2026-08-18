package dev.creatorstore.service;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class UserExperienceService {
  public Map<String, Object> joinCommunity() {
    return Map.of("joined", true, "community", "creator-foundations",
        "joined_at", Instant.now().toString());
  }

  public Map<String, Object> communityStats() {
    return Map.of("members", 1248, "online", 73, "posts_this_week", 186);
  }

  public Map<String, Object> experimentMetadata() {
    return Map.of("flags", Map.of("community", true, "funnels", true,
        "appointments", true, "email_flows", true, "autodm", true),
        "variant", "control");
  }

  public Map<String, Object> saveVariant(Map<String, Object> body) {
    return Map.of("experiment", String.valueOf(body.getOrDefault("experiment", "")),
        "variant", String.valueOf(body.getOrDefault("variant", "control")), "saved", true);
  }
}
