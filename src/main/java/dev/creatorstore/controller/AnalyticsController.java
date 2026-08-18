package dev.creatorstore.controller;

import dev.creatorstore.service.ReportingService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {
  private final ReportingService reporting;

  public AnalyticsController(ReportingService reporting) {
    this.reporting = reporting;
  }

  @GetMapping("/api/v1/analytics")
  public Map<String, Object> analytics(@RequestParam(defaultValue = "1") long creatorId) {
    return reporting.analytics(creatorId);
  }
}
