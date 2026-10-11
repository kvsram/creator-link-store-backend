package com.creatorstore.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /api/auth. Validation annotations are the first line of defence; services re-check. */
public final class AuthDtos {

    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank
            @Size(max = 100)
            // \p{L} letters in any script, \p{M} combining marks (needed for Telugu, Hindi, Arabic... vowel signs),
            // plus space . ' -   At least two letters overall. Digits and symbols are rejected.
            @Pattern(regexp = "^(?=.*\\p{L}.*\\p{L})[\\p{L}\\p{M} .'\\-]+$",
                     message = "must contain only letters, spaces, dots, apostrophes or hyphens")
            String fullName,

            @NotBlank
            @Email
            @Size(max = 254)
            String email,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_.]{3,30}$",
                     message = "must be 3-30 characters: letters, digits, _ or .")
            String username,

            @NotBlank
            @Size(min = 8, max = 72, message = "must be 8-72 characters")   // 72 = BCrypt input limit
            String password,

            @Size(max = 25)
            String phoneNumber      // optional; E.164 with country code, re-validated in the service
    ) {}

    public record SignupResponse(Long creatorId, String username, boolean phoneVerificationSent) {}

    public record AvailabilityResponse(boolean available) {}

    public record LoginRequest(
            @NotBlank @Size(max = 254) String identifier,   // email or username
            @NotBlank @Size(max = 72) String password
    ) {}

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String refreshToken
    ) {
        public static TokenResponse bearer(String accessToken, long expiresInSeconds, String refreshToken) {
            return new TokenResponse(accessToken, "Bearer", expiresInSeconds, refreshToken);
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {}

    public record LogoutRequest(@NotBlank @Size(max = 100) String refreshToken) {}

    public record LogoutAllResponse(int sessionsRevoked) {}
}
