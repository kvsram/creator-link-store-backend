package dev.creatorstore.service;

import dev.creatorstore.repository.TagRepository;
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

  public Map<String, Object> upsert(long creatorId, Map<String, Object> body) {
    String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
    if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    return tags.upsert(creatorId, name);
  }
}
