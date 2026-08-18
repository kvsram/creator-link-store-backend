package dev.creatorstore.controller;

import dev.creatorstore.dto.CustomerRequest;
import dev.creatorstore.service.CustomerService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {
  private final CustomerService customers;

  public CustomerController(CustomerService customers) {
    this.customers = customers;
  }

  @GetMapping("/api/v1/customers")
  public Map<String, Object> list(@RequestParam(defaultValue = "1") long creatorId) {
    return customers.list(creatorId);
  }

  @PostMapping("/api/v1/customers")
  public ResponseEntity<Map<String, Object>> create(@RequestBody CustomerRequest request) {
    return ResponseEntity.status(201).body(customers.create(request));
  }
}
