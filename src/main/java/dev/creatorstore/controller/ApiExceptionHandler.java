package dev.creatorstore.controller;

import dev.creatorstore.support.ApiErrorException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiErrorException.class)
  ResponseEntity<Map<String, Object>> handle(ApiErrorException exception) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", exception.getMessage());
    body.put("code", exception.code());
    body.putAll(exception.details());
    return ResponseEntity.status(exception.status()).body(body);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> handle(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode())
        .body(Map.of("error", exception.getReason() == null ? "request failed" : exception.getReason()));
  }
}
