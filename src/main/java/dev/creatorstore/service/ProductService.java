package dev.creatorstore.service;

import dev.creatorstore.dto.ProductRequest;
import dev.creatorstore.repository.ProductRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {
  private final ProductRepository products;

  public ProductService(ProductRepository products) {
    this.products = products;
  }

  public Map<String, Object> create(long creatorId, ProductRequest request) {
    if (!StoreService.PRODUCT_TYPES.contains(request.type())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported product type");
    }
    if (request.title() == null || request.title().isBlank() || request.description() == null
        || request.priceSubunits() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "title, description, and a non-negative priceSubunits are required");
    }
    String status = request.status() == null ? "draft" : request.status();
    return products.create(creatorId, request.type(), request.title(), request.description(),
        request.priceSubunits(), status, request.position());
  }
}
