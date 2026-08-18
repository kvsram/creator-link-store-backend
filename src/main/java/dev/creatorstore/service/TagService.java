package dev.creatorstore.service;

import dev.creatorstore.repository.TagRepository;
import dev.creatorstore.support.Values;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TagService {
  private final TagRepository tags;

  public TagService(TagRepository tags) {
    this.tags = tags;
  }

  public Map<String, Object> upsert(Map<String, Object> body) {
    long creatorId = Values.longValue(body.getOrDefault("creator_id", 1));
    String name = Values.text(body.get("name")).trim();
    if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    return tags.upsert(creatorId, name);
  }
}
