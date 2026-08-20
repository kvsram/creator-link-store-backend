package dev.creatorstore.controller;

import dev.creatorstore.service.ReportingService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IncomeController {
  private final ReportingService reporting;

  public IncomeController(ReportingService reporting) {
    this.reporting = reporting;
  }

  @GetMapping("/api/v1/income")
  public Map<String, Object> income(HttpServletRequest request) {
    return reporting.income(AuthenticatedCreator.id(request));
  }
}
