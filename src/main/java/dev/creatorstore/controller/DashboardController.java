package dev.creatorstore.controller;

import dev.creatorstore.service.DashboardService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
  private final DashboardService dashboard;

  public DashboardController(DashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping("/api/v1/dashboard")
  public Map<String, Object> dashboard(HttpServletRequest request) {
    return dashboard.dashboard(AuthenticatedCreator.id(request));
  }
}
