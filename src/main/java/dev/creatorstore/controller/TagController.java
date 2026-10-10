package dev.creatorstore.controller;

import dev.creatorstore.identity.AuthenticatedCreator;
import dev.creatorstore.service.TagService;
import jakarta.servlet.http.HttpServletRequest;
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
  public Map<String, Object> upsert(@RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
    return tags.upsert(AuthenticatedCreator.id(request), body);
  }
}
