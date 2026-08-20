package dev.creatorstore.controller;

import dev.creatorstore.service.IntegrationCatalogService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationCatalogController {
  private final IntegrationCatalogService integrations;

  public IntegrationCatalogController(IntegrationCatalogService integrations) {
    this.integrations = integrations;
  }

  @GetMapping("/api/v1/integrations")
  public List<Map<String, Object>> list(HttpServletRequest request) {
    return integrations.list(AuthenticatedCreator.id(request));
  }
}
