package dev.creatorstore.controller;

import dev.creatorstore.service.CreatorService;
import dev.creatorstore.service.ProductConfigurationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicStoreController {
  private final CreatorService creators;
  private final ProductConfigurationService products;

  public PublicStoreController(CreatorService creators, ProductConfigurationService products) {
    this.creators = creators;
    this.products = products;
  }

  @GetMapping("/api/public/{handle}")
  public ResponseEntity<Map<String, Object>> publicPage(@PathVariable String handle) {
    return creators.publicPage(handle).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/api/public/{handle}/products/{productId}")
  public Map<String, Object> publicProduct(@PathVariable String handle,
      @PathVariable long productId) {
    return products.publicDetails(handle, productId);
  }
}
