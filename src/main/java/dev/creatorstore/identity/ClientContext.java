package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;

public record ClientContext(String address, String userAgent, String requestId) {
  public static ClientContext from(HttpServletRequest request) {
    // Spring's configured forwarded-header strategy resolves trusted proxy metadata before this
    // point. Never parse a client-supplied X-Forwarded-For value here.
    return new ClientContext(request.getRemoteAddr(), request.getHeader("User-Agent"),
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
