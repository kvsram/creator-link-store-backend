package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
  private final SessionService sessions;

  AuthInterceptor(SessionService sessions) { this.sessions = sessions; }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    if (isPublicAuthenticationRequest(request)) return true;
    String token = SessionCookies.readToken(request);
    var session = sessions.resolve(token);
    if (session.isPresent()) {
      request.setAttribute("creatorId", session.get().accountId());
      request.setAttribute("accountId", session.get().accountId());
      return true;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"Authentication required.\"}");
    return false;
  }

  private static boolean isPublicAuthenticationRequest(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    if ("POST".equals(method)) {
      return path.equals("/api/v1/auth/registrations")
          || path.equals("/api/v1/auth/email-verifications")
          || path.equals("/api/v1/auth/email-verifications/resend")
          || path.equals("/api/v1/auth/sessions")
          || path.equals("/api/v1/auth/password-reset-requests")
          || path.equals("/api/v1/auth/password-resets");
    }
    return "GET".equals(method) && path.startsWith("/api/v1/auth/test-deliveries/");
  }
}
