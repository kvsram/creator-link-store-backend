package dev.creatorstore.controller;

import dev.creatorstore.service.CreatorService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicStoreController {
  private final CreatorService creators;

  public PublicStoreController(CreatorService creators) {
    this.creators = creators;
  }

  @GetMapping("/api/public/{handle}")
  public ResponseEntity<Map<String, Object>> publicPage(@PathVariable String handle) {
    return creators.publicPage(handle).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
