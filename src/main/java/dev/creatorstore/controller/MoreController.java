package dev.creatorstore.controller;

import dev.creatorstore.service.ContentService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
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
  public Map<String, Object> more(HttpServletRequest request) {
    return content.more(AuthenticatedCreator.id(request));
  }
}
