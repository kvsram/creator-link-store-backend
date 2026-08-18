package dev.creatorstore.support;

public record HttpResult(int status, Object body) {
  public static HttpResult ok(Object body) { return new HttpResult(200, body); }
  public static HttpResult created(Object body) { return new HttpResult(201, body); }
  public static HttpResult accepted(Object body) { return new HttpResult(202, body); }
  public static HttpResult error(int status, String message) {
    return new HttpResult(status, java.util.Map.of("error", message));
  }
  public static HttpResult externalUnavailable(String message) {
    return new HttpResult(503, java.util.Map.of("error", message, "external_service", true));
  }
}
