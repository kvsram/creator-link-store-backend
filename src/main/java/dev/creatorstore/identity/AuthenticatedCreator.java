package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the tenant fixed by Spring Security's authenticated server-side session. */
public final class AuthenticatedCreator {
  private AuthenticatedCreator() {}

  /**
   * Compatibility signature for business controllers while their servlet parameters are removed.
   * The request is deliberately not trusted as an identity source.
   */
  public static long id(HttpServletRequest request) {
    return id();
  }

  public static long id() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof CreatorPrincipal principal)
        || !authentication.isAuthenticated()) {
      throw new IllegalStateException("Authenticated creator is missing from the security context");
    }
    return principal.accountId();
  }
}
