package dev.creatorstore.service;

import dev.creatorstore.repository.ProductRepository;
import dev.creatorstore.repository.StoreRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StoreService {
  public static final List<String> PRODUCT_TYPES = List.of("lead-magnet", "digital-download",
      "meeting", "fulfillment", "course", "membership", "webinar", "community");

  private final StoreRepository stores;
  private final ProductRepository products;

  public StoreService(StoreRepository stores, ProductRepository products) {
    this.stores = stores;
    this.products = products;
  }

  public Map<String, Object> store(long creatorId) {
    return Map.of("store", stores.findDetails(creatorId),
        "products", products.findAll(creatorId), "product_types", PRODUCT_TYPES);
  }
}
