package dev.creatorstore.service;

import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.FeatureRepository;
import dev.creatorstore.repository.StoreRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ContentService {
  private final FeatureRepository features;
  private final CreatorRepository creators;
  private final StoreRepository stores;

  public ContentService(FeatureRepository features, CreatorRepository creators, StoreRepository stores) {
    this.features = features;
    this.creators = creators;
    this.stores = stores;
  }

  public Map<String, Object> success() {
    return Map.of("tutorials", List.of(
        Map.of("id", "launch", "title", "Launch your first offer", "minutes", 8, "category", "Getting started"),
        Map.of("id", "audience", "title", "Turn an audience into customers", "minutes", 12, "category", "Growth"),
        Map.of("id", "pricing", "title", "Price a digital product", "minutes", 10, "category", "Sales")));
  }

  public Map<String, Object> more(long creatorId) {
    return Map.of("funnels", features.funnels(creatorId),
        "appointments", features.appointments(creatorId),
        "features", List.of("funnels", "appointments", "referrals", "email-flows", "autodm"));
  }

  public Map<String, Object> settings(long creatorId) {
    return Map.of("profile", creators.findProfile(creatorId), "store", stores.findSettings(creatorId),
        "notifications", features.notifications(creatorId),
        "tabs", List.of("profile", "integrations", "billing", "payments", "email-notifications", "security"));
  }
}
