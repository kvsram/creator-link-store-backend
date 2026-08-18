package dev.creatorstore.service;

import dev.creatorstore.dto.CustomerRequest;
import dev.creatorstore.repository.CustomerRepository;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerService {
  private final CustomerRepository customers;

  public CustomerService(CustomerRepository customers) {
    this.customers = customers;
  }

  public Map<String, Object> list(long creatorId) {
    return Map.of("items", customers.findAll(creatorId), "limit", 5000);
  }

  public Map<String, Object> create(CustomerRequest request) {
    if (request.email() == null || !request.email().contains("@") || request.name() == null
        || request.name().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and valid email are required");
    }
    try {
      return customers.create(request.creatorId(), request.name(), request.email().toLowerCase(), request.phone());
    } catch (DataIntegrityViolationException conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "customer already exists");
    }
  }
}
