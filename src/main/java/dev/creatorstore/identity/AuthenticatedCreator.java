package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletRequest;

/** Resolves the tenant fixed by the authenticated server-side session. */
public final class AuthenticatedCreator {
  private AuthenticatedCreator() {}

  public static long id(HttpServletRequest request) {
    Object value = request.getAttribute("creatorId");
    if (!(value instanceof Long creatorId)) {
      throw new IllegalStateException("Authenticated creator is missing from the request");
    }
    return creatorId;
  }
}
