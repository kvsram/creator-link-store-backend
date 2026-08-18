package dev.creatorstore.controller;

import dev.creatorstore.service.ContentService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SuccessController {
  private final ContentService content;

  public SuccessController(ContentService content) {
    this.content = content;
  }

  @GetMapping("/api/v1/success")
  public Map<String, Object> success() {
    return content.success();
  }
}
