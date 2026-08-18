package dev.creatorstore.service;

import dev.creatorstore.repository.IntegrationRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IntegrationCatalogService {
  private final IntegrationRepository integrations;

  public IntegrationCatalogService(IntegrationRepository integrations) {
    this.integrations = integrations;
  }

  public List<Map<String, Object>> list(long creatorId) {
    return integrations.findAll(creatorId);
  }
}
