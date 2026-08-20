package dev.creatorstore.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.creatorstore.dto.InstagramAutomationRuleRequest;
import dev.creatorstore.repository.InstagramAutomationRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InstagramAutomationService {
  private final InstagramAutomationRepository repository;
  private final ObjectMapper json;

  public InstagramAutomationService(InstagramAutomationRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
  }

  @Transactional
  public Map<String, Object> createRule(long creatorId, InstagramAutomationRuleRequest request) {
    String mode = request.matchMode() == null ? "any" : request.matchMode().toLowerCase(Locale.ROOT);
    if (request.instagramAccountId() == null || request.instagramAccountId().isBlank()
        || request.mediaId() == null || request.mediaId().isBlank()
        || request.replyText() == null || request.replyText().isBlank()
        || request.replyText().length() > 1000 || !List.of("any", "all", "exact").contains(mode)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "account, media, 1-1000 character replyText, and matchMode any/all/exact are required");
    }
    String keywords = normalizeKeywords(request.keywords());
    if (keywords.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one keyword is required");
    }
    long id = repository.createRule(creatorId, request.instagramAccountId().trim(),
        request.mediaId().trim(), keywords, mode, request.replyText().trim(), request.active());
    return Map.of("automation_id", id, "created", true, "external_service", "instagram",
        "policy", "one private reply per matching comment; seven-day maximum window");
  }

  public List<Map<String, Object>> listRules(long creatorId) {
    return repository.listRules(creatorId);
  }

  /** Parses signed Meta comment events and transactionally enqueues at most one DM per comment. */
  @Transactional
  public int acceptSignedWebhook(byte[] raw) {
    try {
      JsonNode root = json.readTree(raw);
      int queued = 0;
      for (JsonNode entry : iterable(root.path("entry"))) {
        String accountId = text(entry, "id");
        long entryTime = entry.path("time").asLong(Instant.now().getEpochSecond());
        for (JsonNode change : iterable(entry.path("changes"))) {
          if (!"comments".equals(change.path("field").asText())) continue;
          JsonNode value = change.path("value");
          String commentId = first(value, "id", "comment_id");
          String mediaId = first(value.path("media"), "id", "media_id");
          if (mediaId.isBlank()) mediaId = first(value, "media_id");
          if (accountId.isBlank() || mediaId.isBlank() || commentId.isBlank()) continue;
          String comment = value.path("text").asText("");
          JsonNode from = value.path("from");
          Instant occurredAt = Instant.ofEpochSecond(value.path("created_time").asLong(entryTime));
          queued += enqueueFirstMatch(accountId, mediaId, commentId, from.path("id").asText(""),
              from.path("username").asText(""), comment, occurredAt);
        }
      }
      return queued;
    } catch (Exception invalidPayload) {
      return 0;
    }
  }

  private int enqueueFirstMatch(String accountId, String mediaId, String commentId,
      String commenterId, String username, String comment, Instant occurredAt) {
    Map<String, Object> selected = null;
    for (Map<String, Object> rule : repository.matchingRules(accountId, mediaId)) {
      if (matches(comment, String.valueOf(rule.get("keywords")), String.valueOf(rule.get("match_mode")))) {
        selected = rule;
        break;
      }
    }
    if (selected == null) return 0;
    try {
      long eventId = repository.insertComment(commentId, accountId, mediaId, commentId,
          commenterId, username, comment, occurredAt);
      repository.enqueue(eventId, ((Number) selected.get("automation_id")).longValue());
      return 1;
    } catch (DataIntegrityViolationException duplicate) {
      return 0;
    }
  }

  static boolean matches(String comment, String keywordText, String mode) {
    String normalized = comment == null ? "" : comment.trim().toLowerCase(Locale.ROOT);
    List<String> keywords = Arrays.stream(keywordText.split("\\n"))
        .map(String::trim).filter(value -> !value.isBlank()).toList();
    if (keywords.isEmpty()) return false;
    return switch (mode) {
      case "all" -> keywords.stream().allMatch(normalized::contains);
      case "exact" -> keywords.stream().anyMatch(normalized::equals);
      default -> keywords.stream().anyMatch(normalized::contains);
    };
  }

  private static String normalizeKeywords(List<String> keywords) {
    if (keywords == null) return "";
    return keywords.stream().map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
        .filter(value -> !value.isBlank()).distinct().limit(50).collect(Collectors.joining("\n"));
  }

  private static Iterable<JsonNode> iterable(JsonNode node) {
    return () -> StreamSupport.stream(node.spliterator(), false).iterator();
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).asText("");
  }

  private static String first(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = node.path(field).asText("");
      if (!value.isBlank()) return value;
    }
    return "";
  }
}
