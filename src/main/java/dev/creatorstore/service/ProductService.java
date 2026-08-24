package dev.creatorstore.service;

import dev.creatorstore.dto.ProductRequest;
import dev.creatorstore.repository.ProductRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class ProductService {
  private final ProductRepository products;

  public ProductService(ProductRepository products) {
    this.products = products;
  }

  public Map<String, Object> create(long creatorId, ProductRequest request) {
    ProductRequest valid = validate(request);
    return products.create(creatorId, valid.type(), valid.title().trim(), valid.description().trim(),
        valid.priceSubunits(), valid.status(), valid.position());
  }

  public Map<String, Object> update(long creatorId, long id, ProductRequest request) {
    ProductRequest valid = validate(request);
    var rows = products.update(creatorId, id, valid.type(), valid.title().trim(),
        valid.description().trim(), valid.priceSubunits(), valid.status(), valid.position());
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    return rows.get(0);
  }

  public void delete(long creatorId, long id) {
    try {
      if (products.delete(creatorId, id) == 0)
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    } catch (DataIntegrityViolationException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "product has customer or order history and cannot be deleted; unpublish it instead");
    }
  }

  public Map<String, Object> setPinned(long creatorId, long id, boolean pinned) {
    var rows = products.setPinned(creatorId, id, pinned);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    return rows.get(0);
  }

  private ProductRequest validate(ProductRequest request) {
    if (!StoreService.PRODUCT_TYPES.contains(request.type())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported product type");
    }
    if (request.title() == null || request.title().isBlank() || request.description() == null
        || request.priceSubunits() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "title, description, and a non-negative priceSubunits are required");
    }
    String status = request.status() == null ? "draft" : request.status();
    if (!java.util.List.of("draft", "published").contains(status))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be draft or published");
    if (request.title().trim().length() > 100 || request.description().trim().length() > 2000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "product fields exceed their limits");
    return new ProductRequest(0, request.type(), request.title(), request.description(),
        request.priceSubunits(), status, request.position());
  }
}
