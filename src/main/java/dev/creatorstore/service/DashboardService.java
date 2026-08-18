package dev.creatorstore.service;

import dev.creatorstore.repository.MetricsRepository;
import dev.creatorstore.repository.ProductRepository;
import dev.creatorstore.repository.StoreRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final StoreRepository stores;
  private final ProductRepository products;
  private final MetricsRepository metrics;

  public DashboardService(StoreRepository stores, ProductRepository products, MetricsRepository metrics) {
    this.stores = stores;
    this.products = products;
    this.metrics = metrics;
  }

  public Map<String, Object> dashboard(long creatorId) {
    Map<String, Object> store = stores.findSummary(creatorId);
    return Map.of("store", store, "metrics", metrics.metrics(creatorId), "checklist", List.of(
        Map.of("id", "profile", "label", "Complete your profile", "complete", true),
        Map.of("id", "product", "label", "Add a product", "complete", products.count(creatorId) > 0),
        Map.of("id", "payouts", "label", "Enable payouts", "complete",
            Boolean.TRUE.equals(store.get("payouts_enabled")))));
  }
}
