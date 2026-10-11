package com.creatorstore.security;

import com.creatorstore.security.AdminRole;
import com.creatorstore.config.AppProperties;
import com.creatorstore.config.JwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trips tokens through the same beans the application uses, without starting Spring. */
class JwtServiceTest {

    private static AppProperties props(String secret) {
        return new AppProperties(
                "http://localhost:3000",
                new AppProperties.Jwt(secret, "creatorstore",
                        Duration.ofMinutes(15), Duration.ofHours(8), Duration.ofDays(30)),
                new AppProperties.Login(5, Duration.ofMinutes(15)),
                new AppProperties.Verification(Duration.ofMinutes(30), 3),
                new AppProperties.Sms("log"),
                new AppProperties.Admin(new AppProperties.Admin.Bootstrap(null, null, null)));
    }

    private static final String SECRET = "unit-test-secret-at-least-32-characters-long";

    @Test
    void creatorTokenCarriesSubjectRoleAndUsername() {
        JwtConfig config = new JwtConfig();
        AppProperties props = props(SECRET);
        SecretKey key = config.jwtSecretKey(props);
        JwtEncoder encoder = config.jwtEncoder(key);
        JwtDecoder decoder = config.jwtDecoder(key, props);

        JwtService service = new JwtService(encoder, props);
        JwtService.IssuedToken issued = service.issueCreatorToken(42L, "skinglow");

        Jwt jwt = decoder.decode(issued.token());
        assertEquals("42", jwt.getSubject());
        assertEquals("CREATOR", jwt.getClaimAsString("role"));
        assertEquals("skinglow", jwt.getClaimAsString("username"));
        assertNull(jwt.getClaimAsString("admin_role"));
        assertEquals("creatorstore", jwt.getClaimAsString(JwtClaimNames.ISS));

        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        assertEquals(42L, user.id());
        assertEquals(Role.CREATOR, user.role());
    }

    @Test
    void adminTokenCarriesAdminRole() {
        JwtConfig config = new JwtConfig();
        AppProperties props = props(SECRET);
        SecretKey key = config.jwtSecretKey(props);
        JwtService service = new JwtService(config.jwtEncoder(key), props);

        Jwt jwt = config.jwtDecoder(key, props).decode(service.issueAdminToken(7L, AdminRole.FINANCE).token());
        assertEquals("7", jwt.getSubject());
        assertEquals("ADMIN", jwt.getClaimAsString("role"));
        assertEquals("FINANCE", jwt.getClaimAsString("admin_role"));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtConfig config = new JwtConfig();
        AppProperties signer = props(SECRET);
        AppProperties verifier = props("a-different-secret-that-is-also-32-chars!");

        String token = new JwtService(config.jwtEncoder(config.jwtSecretKey(signer)), signer)
                .issueCreatorToken(1L, "x").token();
        JwtDecoder decoder = config.jwtDecoder(config.jwtSecretKey(verifier), verifier);

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }
}
