package dev.creatorstore.service;

import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.StoreRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CreatorService {
  private final CreatorRepository creators;
  private final StoreRepository stores;

  public CreatorService(CreatorRepository creators, StoreRepository stores) {
    this.creators = creators;
    this.stores = stores;
  }

  public Optional<Map<String, Object>> publicPage(String handle) {
    List<Map<String, Object>> creatorRows = creators.findPublicCreator(handle.toLowerCase());
    if (creatorRows.isEmpty()) return Optional.empty();
    Map<String, Object> creator = creatorRows.get(0);
    long creatorId = ((Number) creator.get("id")).longValue();
    List<Map<String, Object>> storeRows = stores.findPublished(creatorId);
    if (storeRows.isEmpty()) return Optional.empty();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("creator", creator);
    response.put("store", storeRows.get(0));
    response.put("links", stores.findPublishedLinks(creatorId));
    response.put("products", stores.findPublishedProducts(creatorId));
    return Optional.of(response);
  }

  public Map<String, Object> user(String handle) {
    return creators.findUser(handle);
  }
}
