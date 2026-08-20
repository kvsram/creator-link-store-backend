package dev.creatorstore.controller;

import dev.creatorstore.dto.CustomerRequest;
import dev.creatorstore.service.CustomerService;
import dev.creatorstore.identity.AuthenticatedCreator;
import jakarta.servlet.http.HttpServletRequest;
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
  public Map<String, Object> list(HttpServletRequest servletRequest) {
    return customers.list(AuthenticatedCreator.id(servletRequest));
  }

  @PostMapping("/api/v1/customers")
  public ResponseEntity<Map<String, Object>> create(@RequestBody CustomerRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(201).body(customers.create(AuthenticatedCreator.id(servletRequest), request));
  }
}
