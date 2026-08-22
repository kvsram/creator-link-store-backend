package dev.creatorstore.service;

import dev.creatorstore.dto.StoreDesignRequest;
import dev.creatorstore.repository.ProductRepository;
import dev.creatorstore.repository.StoreRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

  public Map<String, Object> updateDesign(long creatorId, StoreDesignRequest request) {
    String title = clean(request.title());
    String tagline = clean(request.tagline());
    String theme = clean(request.theme());
    String accent = clean(request.accentColor()).toLowerCase();
    String background = clean(request.backgroundStyle());
    String button = clean(request.buttonStyle());
    String font = clean(request.fontStyle());
    if (title.isBlank() || title.length() > 100 || tagline.length() > 180) {
      throw badRequest("title is required; title and tagline must fit their limits");
    }
    if (!List.of("violet", "sunset", "mint", "midnight").contains(theme)
        || !List.of("soft-gradient", "solid", "spotlight").contains(background)
        || !List.of("rounded", "pill", "square").contains(button)
        || !List.of("modern", "editorial", "friendly").contains(font)
        || !accent.matches("#[0-9a-f]{6}")) {
      throw badRequest("unsupported storefront design value");
    }
    return stores.updateDesign(creatorId, title, tagline, theme, accent, background,
        button, font, !Boolean.FALSE.equals(request.showProducts()),
        !Boolean.FALSE.equals(request.showLinks()));
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
