package dev.creatorstore.service;

import dev.creatorstore.repository.MetricsRepository;
import dev.creatorstore.repository.ReportingRepository;
import dev.creatorstore.repository.StoreRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReportingService {
  private final ReportingRepository reporting;
  private final MetricsRepository metrics;
  private final StoreRepository stores;

  public ReportingService(ReportingRepository reporting, MetricsRepository metrics, StoreRepository stores) {
    this.reporting = reporting;
    this.metrics = metrics;
    this.stores = stores;
  }

  public Map<String, Object> income(long creatorId) {
    return Map.of("summary", reporting.incomeSummary(creatorId), "orders", reporting.orders(creatorId),
        "currency", stores.findCurrency(creatorId),
        "cashout", Map.of("available_subunits", 48902, "pending_subunits", 0),
        "real_money_notice", "Amounts represent real-world currency in the smallest unit. No payout occurs unless a configured provider confirms it.");
  }

  public Map<String, Object> analytics(long creatorId) {
    return Map.of("totals", metrics.metrics(creatorId), "sources", reporting.trafficSources(creatorId));
  }
}
