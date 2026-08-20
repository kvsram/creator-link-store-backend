package dev.creatorstore.controller;

import dev.creatorstore.service.ReportingService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
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
  public Map<String, Object> analytics(HttpServletRequest request) {
    return reporting.analytics(AuthenticatedCreator.id(request));
  }
}
