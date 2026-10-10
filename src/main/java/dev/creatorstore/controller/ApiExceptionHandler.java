package dev.creatorstore.controller;

import dev.creatorstore.support.ApiErrorException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException exception) {
    Map<String, String> fields = new LinkedHashMap<>();
    exception.getBindingResult().getFieldErrors().forEach(error ->
        fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "Check the highlighted fields and try again.");
    body.put("code", "VALIDATION_ERROR");
    body.put("fields", fields);
    return ResponseEntity.badRequest().body(body);
  }

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
