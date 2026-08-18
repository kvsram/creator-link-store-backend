package dev.creatorstore.controller;

import dev.creatorstore.service.ContentService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MoreController {
  private final ContentService content;

  public MoreController(ContentService content) {
    this.content = content;
  }

  @GetMapping("/api/v1/more")
  public Map<String, Object> more(@RequestParam(defaultValue = "1") long creatorId) {
    return content.more(creatorId);
  }
}
