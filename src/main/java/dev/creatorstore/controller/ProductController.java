package dev.creatorstore.controller;

import dev.creatorstore.dto.ProductRequest;
import dev.creatorstore.service.ProductService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
  private final ProductService products;

  public ProductController(ProductService products) {
    this.products = products;
  }

  @PostMapping("/api/v1/products")
  public ResponseEntity<Map<String, Object>> create(@RequestBody ProductRequest request) {
    return ResponseEntity.status(201).body(products.create(request));
  }
}
