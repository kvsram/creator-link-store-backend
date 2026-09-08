package dev.creatorstore.service;

import dev.creatorstore.domain.ProductTypeConfiguration;
import dev.creatorstore.dto.ProductRequest;
import dev.creatorstore.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {
  private final ProductRepository products;
  private final ProductConfigurationService configurations;

  public ProductService(ProductRepository products, ProductConfigurationService configurations) {
    this.products = products;
    this.configurations = configurations;
  }

  @Transactional
  public Map<String, Object> create(long creatorId, ProductRequest request) {
    return create(creatorId, null, request);
  }

  @Transactional
  public Map<String, Object> create(long creatorId, String idempotencyKey,
      ProductRequest request) {
    ProductRequest valid = validate(request, "draft");
    String creationKey = validateIdempotencyKey(idempotencyKey);
    String subtitle = configurations.subtitle(valid.subtitle());
    String callToAction = configurations.callToAction(valid.callToAction());
    String thumbnailStyle = configurations.thumbnailStyle(valid.thumbnailStyle());
    ProductTypeConfiguration configuration = configurations.validate(valid.type(), valid.configuration());
    Map<String, Object> created = products.create(creatorId, valid.type(), valid.title().trim(),
        valid.description().trim(), valid.priceSubunits(), valid.status(), valid.position(), subtitle,
        callToAction, thumbnailStyle, configurations.encode(configuration), creationKey);
    long productId = ((Number) created.get("id")).longValue();
    if (Boolean.FALSE.equals(created.get("_created")))
      return configurations.creatorDetails(creatorId, productId);
    configuration = configurations.synchronizeAndStore(creatorId, productId, valid.type(),
        configuration, subtitle, callToAction, thumbnailStyle);
    if ("published".equals(valid.status()))
      configurations.ensurePublishReady(creatorId, productId, valid.type(), configuration,
          valid.priceSubunits());
    return configurations.creatorDetails(creatorId, productId);
  }

  @Transactional
  public Map<String, Object> update(long creatorId, long id, ProductRequest request) {
    List<Map<String, Object>> existingRows = products.findOwned(creatorId, id);
    if (existingRows.isEmpty()) throw notFound();
    Map<String, Object> existing = existingRows.get(0);
    String existingType = String.valueOf(existing.get("type"));
    String requestedType = request.type() == null ? existingType : request.type();
    if (!existingType.equals(requestedType))
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "product type cannot be changed; create a new product instead");

    String existingStatus = String.valueOf(existing.get("status"));
    ProductRequest valid = validate(new ProductRequest(0, requestedType, request.title(),
        request.description(), request.priceSubunits(), request.status() == null ? existingStatus : request.status(),
        request.position(), request.subtitle(), request.callToAction(), request.thumbnailStyle(),
        request.configuration()), existingStatus);
    String subtitle = valid.subtitle() == null
        ? String.valueOf(existing.getOrDefault("subtitle", "")) : configurations.subtitle(valid.subtitle());
    String callToAction = valid.callToAction() == null
        ? String.valueOf(existing.getOrDefault("call_to_action", "Get access"))
        : configurations.callToAction(valid.callToAction());
    String thumbnailStyle = valid.thumbnailStyle() == null
        ? String.valueOf(existing.getOrDefault("thumbnail_style", "preview"))
        : configurations.thumbnailStyle(valid.thumbnailStyle());
    Map<String, Object> rawConfiguration = valid.configuration() == null
        ? configurations.parseSnapshot(existing.get("configuration_json")) : valid.configuration();
    ProductTypeConfiguration configuration = configurations.validate(existingType, rawConfiguration);

    List<Map<String, Object>> rows = products.update(creatorId, id, valid.title().trim(),
        valid.description().trim(), valid.priceSubunits(), valid.status(), valid.position(), subtitle,
        callToAction, thumbnailStyle);
    if (rows.isEmpty()) throw notFound();
    configuration = configurations.synchronizeAndStore(creatorId, id, existingType, configuration,
        subtitle, callToAction, thumbnailStyle);
    if ("published".equals(valid.status()))
      configurations.ensurePublishReady(creatorId, id, existingType, configuration,
          valid.priceSubunits());
    return configurations.creatorDetails(creatorId, id);
  }

  public void delete(long creatorId, long id) {
    try {
      if (products.delete(creatorId, id) == 0) throw notFound();
    } catch (DataIntegrityViolationException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "product has customer or order history and cannot be deleted; unpublish it instead");
    }
  }

  public Map<String, Object> setPinned(long creatorId, long id, boolean pinned) {
    var rows = products.setPinned(creatorId, id, pinned);
    if (rows.isEmpty()) throw notFound();
    return rows.get(0);
  }

  private ProductRequest validate(ProductRequest request, String defaultStatus) {
    if (request == null || !StoreService.PRODUCT_TYPES.contains(request.type()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported product type");
    if (request.title() == null || request.title().isBlank() || request.description() == null
        || request.priceSubunits() < 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "title, description, and a non-negative priceSubunits are required");
    if ("lead-magnet".equals(request.type()) && request.priceSubunits() != 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lead magnets must be free");
    String status = request.status() == null ? defaultStatus : request.status();
    if (!List.of("draft", "published").contains(status))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "status must be draft or published");
    if (request.title().trim().length() > 100 || request.description().trim().length() > 2000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "product fields exceed their limits");
    return new ProductRequest(0, request.type(), request.title(), request.description(),
        request.priceSubunits(), status, request.position(), request.subtitle(),
        request.callToAction(), request.thumbnailStyle(), request.configuration());
  }

  private static String validateIdempotencyKey(String value) {
    if (value == null || value.isBlank()) return null;
    String key = value.trim();
    if (key.length() > 120)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Idempotency-Key must be at most 120 characters");
    return key;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
  }
}
