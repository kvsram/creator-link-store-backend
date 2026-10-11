package com.creatorstore.auth;

import com.creatorstore.common.ApiException;
import com.creatorstore.common.Tokens;
import com.creatorstore.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Creator sessions: issue, rotate, revoke. Admins deliberately have no refresh tokens (see HANDOFF.md). */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** The raw token for the client plus the row it belongs to. */
    public record Issued(String rawToken, RefreshToken row) {}

    private final RefreshTokenRepository tokens;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository tokens, AppProperties props) {
        this.tokens = tokens;
        this.ttl = props.jwt().refreshTokenTtl();
    }

    /** New session (login): a new family. */
    @Transactional
    public Issued issue(Long creatorId, ClientInfo client) {
        return create(creatorId, UUID.randomUUID(), Instant.now(), client);
    }

    /**
     * Rotation. Exactly one of three things happens:
     *  - valid: the presented token is revoked and replaced by a new one in the same family;
     *  - revoked already: reuse detected, the whole family is revoked, 401;
     *  - expired or unknown: 401.
     *
     * noRollbackFor: the family revocation in the reuse branch MUST be committed even though we then
     * throw a 401. Without this, Spring would roll the revocation back together with the exception.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Issued rotate(String rawToken, ClientInfo client) {
        RefreshToken current = tokens.findByTokenHash(Tokens.sha256(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Please log in again."));

        Instant now = Instant.now();
        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for creator {}; revoked {} session(s) in family {}",
                    current.getCreatorId(), revoked, current.getFamilyId());
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED", "Your session was invalidated. Please log in again.");
        }
        if (current.isExpired(now)) {
            throw ApiException.unauthorized("REFRESH_TOKEN_EXPIRED", "Your session has expired. Please log in again.");
        }

        Issued next = create(current.getCreatorId(), current.getFamilyId(), now, client);
        current.rotateTo(next.row(), now);
        return next;
    }

    /** Logout from one device. Idempotent; unknown tokens are ignored so logout never fails. */
    @Transactional
    public void revoke(String rawToken) {
        tokens.findByTokenHash(Tokens.sha256(rawToken)).ifPresent(t -> t.revoke(Instant.now()));
    }

    /** Logout everywhere. */
    @Transactional
    public int revokeAll(Long creatorId) {
        return tokens.revokeAllForCreator(creatorId, Instant.now());
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void deleteOldTokens() {
        int deleted = tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(30)));
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    private Issued create(Long creatorId, UUID familyId, Instant now, ClientInfo client) {
        String raw = Tokens.newRawToken();
        RefreshToken row = tokens.save(new RefreshToken(
                creatorId, familyId, Tokens.sha256(raw), now, now.plus(ttl),
                client.userAgent(), client.ipAddress()));
        return new Issued(raw, row);
    }
}
