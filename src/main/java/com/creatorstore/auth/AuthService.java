package com.creatorstore.auth;

import com.creatorstore.auth.AuthDtos.LoginRequest;
import com.creatorstore.auth.AuthDtos.SignupRequest;
import com.creatorstore.auth.AuthDtos.SignupResponse;
import com.creatorstore.auth.AuthDtos.TokenResponse;
import com.creatorstore.common.ApiException;
import com.creatorstore.creator.Creator;
import com.creatorstore.creator.CreatorService;
import com.creatorstore.security.JwtService;
import com.creatorstore.verification.PhoneVerificationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creator authentication use cases. Orchestrates CreatorService, PhoneVerificationService,
 * RefreshTokenService and JwtService; owns no table of its own except refresh_tokens (via RefreshTokenService).
 */
@Service
public class AuthService {

    private final CreatorService creatorService;
    private final PhoneVerificationService phoneVerification;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * A real BCrypt hash of a random value, computed once at startup. When the identifier is unknown we still
     * run one BCrypt comparison against this, so "no such account" takes as long as "wrong password" and
     * attackers cannot learn which emails exist from response timing.
     */
    private final String dummyHash;

    public AuthService(CreatorService creatorService,
                       PhoneVerificationService phoneVerification,
                       RefreshTokenService refreshTokens,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder) {
        this.creatorService = creatorService;
        this.phoneVerification = phoneVerification;
        this.refreshTokens = refreshTokens;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public boolean isUsernameAvailable(String rawUsername) {
        return creatorService.isUsernameAvailable(rawUsername);
    }

    /** Account and first verification link are created together: either both exist or neither does. */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        Creator creator = creatorService.register(
                request.fullName(), request.email(), request.username(), request.password(), request.phoneNumber());

        boolean sent = false;
        if (creator.getPhoneNumber() != null) {
            sent = phoneVerification.issueLink(creator.getId());
        }
        return new SignupResponse(creator.getId(), creator.getUsername(), sent);
    }

    /**
     * NOT @Transactional on purpose: the failed-attempt counter must be committed even though we then
     * throw an exception, so bookkeeping runs in CreatorService's own transactions.
     */
    public TokenResponse login(LoginRequest request, ClientInfo client) {
        Instant now = Instant.now();
        Optional<Creator> found = creatorService.findByLoginIdentifier(request.identifier());

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);   // same cost as a real comparison
            throw invalidCredentials();
        }
        Creator creator = found.get();

        if (!creator.canLogin()) {
            // Same message as a wrong password: do not reveal account state to strangers.
            throw invalidCredentials();
        }
        if (creator.isLocked(now)) {
            throw ApiException.tooManyRequests("ACCOUNT_LOCKED",
                    "Too many failed attempts. Please try again in a few minutes.");
        }
        if (!passwordEncoder.matches(request.password(), creator.getPasswordHash())) {
            creatorService.recordFailedLogin(creator.getId(), now);
            throw invalidCredentials();
        }

        creatorService.recordSuccessfulLogin(creator.getId(), now);
        RefreshTokenService.Issued session = refreshTokens.issue(creator.getId(), client);
        return tokens(creator, session.rawToken());
    }

    /** Rotates the refresh token and issues a new access token. */
    public TokenResponse refresh(String rawRefreshToken, ClientInfo client) {
        RefreshTokenService.Issued session = refreshTokens.rotate(rawRefreshToken, client);
        Creator creator = creatorService.getById(session.row().getCreatorId());
        if (!creator.canLogin()) {
            refreshTokens.revokeAll(creator.getId());
            throw ApiException.unauthorized("ACCOUNT_NOT_ACTIVE", "This account is not active.");
        }
        return tokens(creator, session.rawToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    public int logoutAll(Long creatorId) {
        return refreshTokens.revokeAll(creatorId);
    }

    private TokenResponse tokens(Creator creator, String rawRefreshToken) {
        JwtService.IssuedToken access = jwtService.issueCreatorToken(creator.getId(), creator.getUsername());
        return TokenResponse.bearer(access.token(), jwtService.accessTokenTtl().toSeconds(), rawRefreshToken);
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email/username or password.");
    }
}
