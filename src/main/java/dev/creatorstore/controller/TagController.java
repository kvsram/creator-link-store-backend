package dev.creatorstore.controller;

import dev.creatorstore.service.TagService;
import java.util.Map;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TagController {
  private final TagService tags;

  public TagController(TagService tags) {
    this.tags = tags;
  }

  @PutMapping("/api/v1/tags")
  public Map<String, Object> upsert(@RequestBody Map<String, Object> body) {
    return tags.upsert(body);
  }
}
