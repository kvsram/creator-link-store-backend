package com.creatorstore.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller of the current request, read from the verified JWT.
 *
 * Usage in a controller:
 * <pre>
 *   public X handler(@AuthenticationPrincipal Jwt jwt) {
 *       AuthenticatedUser user = AuthenticatedUser.from(jwt);
 *       ...
 *   }
 * </pre>
 */
public record AuthenticatedUser(Long id, Role role) {

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                Long.parseLong(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString("role")));
    }

    public boolean isCreator() {
        return role == Role.CREATOR;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
