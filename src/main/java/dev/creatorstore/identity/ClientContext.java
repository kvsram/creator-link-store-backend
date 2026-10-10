package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;

public record ClientContext(String address, String userAgent, String requestId) {
  public static ClientContext from(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    String address = forwarded == null || forwarded.isBlank()
        ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
    return new ClientContext(address, request.getHeader("User-Agent"),
        safeRequestId(request.getHeader("X-Request-Id")));
  }

  public static ClientContext unknown() {
    return new ClientContext("unknown", null, null);
  }

  private static String safeRequestId(String value) {
    if (value == null) return null;
    String candidate = value.trim();
    return candidate.matches("[A-Za-z0-9._:-]{1,80}") ? candidate : null;
  }
}
