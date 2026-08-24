package dev.creatorstore.controller;

import dev.creatorstore.dto.PromotionRequest;
import dev.creatorstore.identity.AuthenticatedCreator;
import dev.creatorstore.service.PromotionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PromotionController {
  private final PromotionService promotions;

  public PromotionController(PromotionService promotions) { this.promotions = promotions; }

  @PostMapping("/api/v1/promotions")
  public ResponseEntity<Map<String, Object>> create(@RequestBody PromotionRequest body, HttpServletRequest request) {
    return ResponseEntity.status(201).body(promotions.create(AuthenticatedCreator.id(request), body));
  }

  @PatchMapping("/api/v1/promotions/{id}")
  public Map<String, Object> update(@PathVariable long id, @RequestBody PromotionRequest body, HttpServletRequest request) {
    return promotions.update(AuthenticatedCreator.id(request), id, body);
  }

  @PatchMapping("/api/v1/promotions/{id}/pin")
  public Map<String, Object> setPinned(@PathVariable long id,
      @org.springframework.web.bind.annotation.RequestParam boolean pinned,
      HttpServletRequest request) {
    return promotions.setPinned(AuthenticatedCreator.id(request), id, pinned);
  }

  @DeleteMapping("/api/v1/promotions/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
    promotions.delete(AuthenticatedCreator.id(request), id);
    return ResponseEntity.noContent().build();
  }
}
