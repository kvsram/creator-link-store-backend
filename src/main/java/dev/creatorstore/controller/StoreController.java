package dev.creatorstore.controller;

import dev.creatorstore.service.StoreService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StoreController {
  private final StoreService stores;

  public StoreController(StoreService stores) {
    this.stores = stores;
  }

  @GetMapping("/api/v1/store")
  public Map<String, Object> store(HttpServletRequest request) {
    return stores.store(AuthenticatedCreator.id(request));
  }
}
