package dev.creatorstore.controller;

import dev.creatorstore.service.AutomationService;
import dev.creatorstore.service.InstagramAutomationService;
import dev.creatorstore.dto.InstagramAutomationRuleRequest;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutomationController {
  private final AutomationService automations;
  private final InstagramAutomationService instagramAutomations;

  public AutomationController(AutomationService automations,
      InstagramAutomationService instagramAutomations) {
    this.automations = automations;
    this.instagramAutomations = instagramAutomations;
  }

  @GetMapping("/api/v1/automations/instagram-posts-metadata")
  public Map<String, Object> instagramMetadata(HttpServletRequest request) {
    return automations.instagramMetadata(AuthenticatedCreator.id(request));
  }

  @GetMapping("/api/v1/automations/analytics")
  public Map<String, Object> analytics(
      @RequestParam(name = "automation_ids", required = false) List<Long> ids,
      HttpServletRequest request) {
    return automations.analytics(AuthenticatedCreator.id(request), ids);
  }

  @GetMapping("/api/v1/automations/instagram-comment-rules")
  public List<Map<String, Object>> commentRules(HttpServletRequest request) {
    return instagramAutomations.listRules(AuthenticatedCreator.id(request));
  }

  @PostMapping("/api/v1/automations/instagram-comment-rules")
  public ResponseEntity<Map<String, Object>> createCommentRule(
      @RequestBody InstagramAutomationRuleRequest body, HttpServletRequest request) {
    return ResponseEntity.status(201)
        .body(instagramAutomations.createRule(AuthenticatedCreator.id(request), body));
  }
}
