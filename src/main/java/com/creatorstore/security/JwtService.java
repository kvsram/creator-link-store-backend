package com.creatorstore.security;

import com.creatorstore.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Issues short-lived access tokens. Verification is done by Spring's resource-server filter
 * (see {@link com.creatorstore.config.JwtConfig} and {@link com.creatorstore.config.SecurityConfig}).
 *
 * Claims:
 *   sub        - creator id or admin id (as a string)
 *   role       - "CREATOR" or "ADMIN"
 *   username   - creators only
 *   admin_role - admins only: SUPER_ADMIN | SUPPORT | FINANCE
 */
@Service
public class JwtService {

    public record IssuedToken(String token, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final AppProperties.Jwt jwt;

    public JwtService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.jwt = props.jwt();
    }

    public IssuedToken issueCreatorToken(Long creatorId, String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwt.accessTokenTtl());
        JwtClaimsSet claims = baseClaims(now, expiresAt, creatorId, Role.CREATOR)
                .claim("username", username)
                .build();
        return new IssuedToken(encode(claims), expiresAt);
    }

    public IssuedToken issueAdminToken(Long adminId, AdminRole adminRole) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwt.adminAccessTokenTtl());
        JwtClaimsSet claims = baseClaims(now, expiresAt, adminId, Role.ADMIN)
                .claim("admin_role", adminRole.name())
                .build();
        return new IssuedToken(encode(claims), expiresAt);
    }

    public Duration accessTokenTtl() {
        return jwt.accessTokenTtl();
    }

    private JwtClaimsSet.Builder baseClaims(Instant now, Instant expiresAt, Long subjectId, Role role) {
        return JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(subjectId))
                .claim("role", role.name());
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
