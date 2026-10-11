package com.creatorstore.admin;

import com.creatorstore.security.AdminRole;
import com.creatorstore.common.ApiException;
import com.creatorstore.common.Normalizer;
import com.creatorstore.config.AppProperties;
import com.creatorstore.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin login. Admins get a longer-lived access token (app.jwt.admin-access-token-ttl) and no refresh token.
 * Rationale: a handful of staff on known devices; simpler than a second session table. Revisit if admins grow.
 */
@Service
public class AdminAuthService {

    public record AdminLoginResult(String accessToken, long expiresInSeconds, AdminRole role) {}

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties.Login loginPolicy;
    private final AppProperties.Jwt jwtPolicy;

    /** See AuthService.dummyHash: keeps "unknown email" as slow as "wrong password". */
    private final String dummyHash;

    public AdminAuthService(AdminUserRepository admins, PasswordEncoder passwordEncoder,
                            JwtService jwtService, AppProperties props) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginPolicy = props.login();
        this.jwtPolicy = props.jwt();
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Not @Transactional for the same reason as creator login: a failed attempt must be committed
     * even though we throw afterwards. The repository's update methods run in their own transactions.
     */
    public AdminLoginResult login(String rawEmail, String password) {
        Instant now = Instant.now();
        Optional<AdminUser> found = admins.findByEmail(Normalizer.email(rawEmail));

        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        AdminUser admin = found.get();

        if (!admin.isActive()) {
            throw invalidCredentials();
        }
        if (admin.isLocked(now)) {
            throw ApiException.tooManyRequests("ACCOUNT_LOCKED",
                    "Too many failed attempts. Please try again in a few minutes.");
        }
        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            admins.recordFailedLogin(admin.getId(), loginPolicy.maxFailedAttempts(), now.plus(loginPolicy.lockDuration()));
            throw invalidCredentials();
        }

        admins.recordSuccessfulLogin(admin.getId(), now);
        JwtService.IssuedToken token = jwtService.issueAdminToken(admin.getId(), admin.getRole());
        return new AdminLoginResult(token.token(), jwtPolicy.adminAccessTokenTtl().toSeconds(), admin.getRole());
    }

    @Transactional(readOnly = true)
    public AdminUser getById(Long id) {
        return admins.findById(id)
                .orElseThrow(() -> ApiException.notFound("ADMIN_NOT_FOUND", "Admin not found."));
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password.");
    }
}
