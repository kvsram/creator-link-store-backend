package dev.creatorstore.controller;

import dev.creatorstore.dto.ProductRequest;
import dev.creatorstore.service.ProductService;
import dev.creatorstore.service.ProductConfigurationService;
import dev.creatorstore.service.ProductFileService;
import dev.creatorstore.dto.ProductConfigurationRequest;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductController {
  private final ProductService products;
  private final ProductConfigurationService configurations;
  private final ProductFileService files;

  public ProductController(ProductService products, ProductConfigurationService configurations,
      ProductFileService files) {
    this.products = products;
    this.configurations = configurations;
    this.files = files;
  }

  @PostMapping("/api/v1/products")
  public ResponseEntity<Map<String, Object>> create(@RequestBody ProductRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(201).body(products.create(AuthenticatedCreator.id(servletRequest), request));
  }

  @PatchMapping("/api/v1/products/{id}")
  public Map<String, Object> update(@PathVariable long id, @RequestBody ProductRequest request,
      HttpServletRequest servletRequest) {
    return products.update(AuthenticatedCreator.id(servletRequest), id, request);
  }

  @DeleteMapping("/api/v1/products/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest servletRequest) {
    products.delete(AuthenticatedCreator.id(servletRequest), id);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/api/v1/products/{id}/pin")
  public Map<String, Object> pin(@PathVariable long id, @RequestParam boolean pinned,
      HttpServletRequest servletRequest) {
    return products.setPinned(AuthenticatedCreator.id(servletRequest), id, pinned);
  }

  @PutMapping("/api/v1/products/{id}/configuration")
  public Map<String, Object> configure(@PathVariable long id,
      @RequestBody ProductConfigurationRequest body, HttpServletRequest request) {
    return configurations.update(AuthenticatedCreator.id(request), id, body);
  }

  @PostMapping(value = "/api/v1/products/{id}/files", consumes = "multipart/form-data")
  public ResponseEntity<Map<String, Object>> upload(@PathVariable long id,
      @RequestParam(defaultValue = "download") String kind,
      @RequestPart("file") MultipartFile file, HttpServletRequest request) {
    return ResponseEntity.status(201).body(files.upload(AuthenticatedCreator.id(request), id, kind, file));
  }

  @GetMapping("/api/v1/products/{id}/files")
  public java.util.List<Map<String, Object>> files(@PathVariable long id, HttpServletRequest request) {
    return files.files(AuthenticatedCreator.id(request), id);
  }
}
