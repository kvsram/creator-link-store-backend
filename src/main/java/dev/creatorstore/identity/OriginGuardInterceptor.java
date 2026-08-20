package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Rejects browser credential requests from origins outside the configured frontend set. */
@Component
public class OriginGuardInterceptor implements HandlerInterceptor {
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  private final Set<String> allowedOrigins;

  public OriginGuardInterceptor(
      @Value("${app.allowed-origins:http://localhost:5173,http://localhost:3000}") String origins) {
    this.allowedOrigins = Arrays.stream(origins.split(","))
        .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (SAFE_METHODS.contains(request.getMethod())) return true;
    String origin = request.getHeader("Origin");
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    if ((origin != null && !origin.isBlank() && !allowedOrigins.contains(origin))
        || ((origin == null || origin.isBlank()) && "cross-site".equalsIgnoreCase(fetchSite))) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Origin is not allowed.\"}");
      return false;
    }
    return true;
  }
}
