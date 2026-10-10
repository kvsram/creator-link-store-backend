package dev.creatorstore.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and revokes opaque browser sessions while storing only token hashes. */
@Service
public class SessionService {
  private static final Duration ABSOLUTE_TTL = Duration.ofDays(30);
  private static final Duration IDLE_TTL = Duration.ofHours(12);
  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

  private final JdbcTemplate database;
  private final boolean secureCookies;
  private final SecurityFingerprintService fingerprints;

  public SessionService(JdbcTemplate database,
      @Value("${app.secure-cookies:false}") boolean secureCookies,
      SecurityFingerprintService fingerprints) {
    this.database = database;
    this.secureCookies = secureCookies;
    this.fingerprints = fingerprints;
  }

  public IssuedSession issue(long creatorId) {
    return issue(creatorId, ClientContext.unknown(), "password");
  }

  public IssuedSession issue(long accountId, ClientContext context, String authMethod) {
    String token = SessionCookies.newToken();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime expiresAt = now.plus(ABSOLUTE_TTL);
    Integer version = database.queryForObject(
        "select session_version from accounts where id=? and status='active'",
        Integer.class, accountId);
    if (version == null) throw new IllegalStateException("Active account required for session");
    database.update("insert into auth_sessions(account_id,token_hash,session_version,auth_method,"
            + "idle_expires_at,absolute_expires_at,ip_hmac,user_agent_hash) values(?,?,?,?,?,?,?,?)",
        accountId, SessionCookies.hash(token), version, authMethod, now.plus(IDLE_TTL), expiresAt,
        fingerprints.fingerprint(context.address()), fingerprints.fingerprint(context.userAgent()));
    return new IssuedSession(token, expiresAt);
  }

  public Optional<AuthenticatedSession> resolve(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<AuthenticatedSession> rows = database.query(
        "select s.account_id,s.public_id,s.created_at,s.last_seen_at,s.absolute_expires_at "
            + "from auth_sessions s join accounts a on a.id=s.account_id "
            + "left join sessions legacy on legacy.id=s.token_hash "
            + "where s.token_hash=? and s.revoked_at is null and s.idle_expires_at>? "
            + "and s.absolute_expires_at>? and s.session_version=a.session_version "
            + "and a.status='active' and (legacy.id is null or legacy.revoked_at is null)",
        (rs, row) -> new AuthenticatedSession(rs.getLong("account_id"),
            UUID.fromString(rs.getString("public_id")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class),
            rs.getObject("absolute_expires_at", OffsetDateTime.class)),
        SessionCookies.hash(token), now, now);
    if (rows.isEmpty()) return Optional.empty();
    AuthenticatedSession session = rows.get(0);
    if (session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
      OffsetDateTime idleExpiry = now.plus(IDLE_TTL).isBefore(session.absoluteExpiresAt())
          ? now.plus(IDLE_TTL) : session.absoluteExpiresAt();
      database.update("update auth_sessions set last_seen_at=?,idle_expires_at=? "
          + "where public_id=? and revoked_at is null", now, idleExpiry, session.publicId());
    }
    return Optional.of(session);
  }

  @Transactional
  public void revoke(String token) {
    if (token != null) {
      database.update(
          "update auth_sessions set revoked_at=current_timestamp,revocation_reason='logout' "
              + "where token_hash=? and revoked_at is null",
          SessionCookies.hash(token));
      database.update("update sessions set revoked_at=current_timestamp where id=? "
          + "and revoked_at is null", SessionCookies.hash(token));
    }
  }

  @Transactional
  public boolean revoke(long accountId, UUID publicId) {
    database.update("update sessions set revoked_at=current_timestamp where id in "
        + "(select token_hash from auth_sessions where account_id=? and public_id=?) "
        + "and revoked_at is null", accountId, publicId);
    return database.update("update auth_sessions set revoked_at=current_timestamp,"
        + "revocation_reason='device_revoked' where account_id=? and public_id=? "
        + "and revoked_at is null", accountId, publicId) > 0;
  }

  public List<SessionView> list(long accountId, String currentToken) {
    String currentHash = currentToken == null ? "" : SessionCookies.hash(currentToken);
    return database.query("select s.public_id,s.created_at,s.last_seen_at,s.idle_expires_at,"
            + "absolute_expires_at,auth_method,user_agent_hash,(token_hash=?) as is_current "
            + "from auth_sessions s join accounts a on a.id=s.account_id "
            + "left join sessions legacy on legacy.id=s.token_hash "
            + "where s.account_id=? and s.revoked_at is null and a.status='active' "
            + "and s.session_version=a.session_version "
            + "and s.idle_expires_at>current_timestamp "
            + "and s.absolute_expires_at>current_timestamp "
            + "and (legacy.id is null or legacy.revoked_at is null) "
            + "order by s.created_at desc",
        (rs, row) -> new SessionView(UUID.fromString(rs.getString("public_id")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class),
            rs.getObject("idle_expires_at", OffsetDateTime.class),
            rs.getObject("absolute_expires_at", OffsetDateTime.class),
            rs.getString("auth_method"),
            rs.getString("user_agent_hash") == null ? "Unknown device" : "Recognized device",
            "Not collected",
            rs.getBoolean("is_current")), currentHash, accountId);
  }

  public ResponseCookie cookie(IssuedSession session, boolean requestIsSecure) {
    return ResponseCookie.from(SessionCookies.COOKIE_NAME, session.token())
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .maxAge(ABSOLUTE_TTL)
        .secure(secureCookies || requestIsSecure)
        .build();
  }

  public ResponseCookie clearedCookie(boolean requestIsSecure) {
    return ResponseCookie.from(SessionCookies.COOKIE_NAME, "")
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .maxAge(0)
        .secure(secureCookies || requestIsSecure)
        .build();
  }

  public record IssuedSession(String token, OffsetDateTime expiresAt) {}

  public record AuthenticatedSession(long accountId, UUID publicId, OffsetDateTime createdAt,
                                     OffsetDateTime lastSeenAt,
                                     OffsetDateTime absoluteExpiresAt) {}

  public record SessionView(UUID publicId, OffsetDateTime createdAt, OffsetDateTime lastSeenAt,
                            OffsetDateTime idleExpiresAt, OffsetDateTime expiresAt,
                            String authMethod, String device, String location, boolean current) {}
}
