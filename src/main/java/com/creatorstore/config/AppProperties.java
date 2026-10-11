package com.creatorstore.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Every "app.*" setting from application.yml, typed and validated at startup.
 * The application refuses to start if a required value (for example the JWT secret) is missing or too short.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String baseUrl,
        @NotNull @Valid Jwt jwt,
        @NotNull @Valid Login login,
        @NotNull @Valid Verification verification,
        @NotNull @Valid Sms sms,
        @NotNull Admin admin
) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "app.jwt.secret must be at least 32 characters") String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration adminAccessTokenTtl,
            @NotNull Duration refreshTokenTtl
    ) {}

    public record Login(
            @Min(1) int maxFailedAttempts,
            @NotNull Duration lockDuration
    ) {}

    public record Verification(
            @NotNull Duration linkTtl,
            @Min(1) int maxLinksPerPhonePerHour
    ) {}

    public record Sms(
            @NotBlank String provider
    ) {}

    public record Admin(Bootstrap bootstrap) {
        public record Bootstrap(String email, String password, String fullName) {}
    }
}
