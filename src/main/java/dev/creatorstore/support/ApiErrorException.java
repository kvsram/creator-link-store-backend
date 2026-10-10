package dev.creatorstore.support;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiErrorException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> details;

  public ApiErrorException(HttpStatus status, String message, String code,
                           Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public HttpStatus status() { return status; }
  public String code() { return code; }
  public Map<String, Object> details() { return details; }
}
