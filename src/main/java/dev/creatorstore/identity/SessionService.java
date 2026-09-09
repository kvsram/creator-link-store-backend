package dev.creatorstore.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Creates and revokes opaque browser sessions while storing only token hashes. */
@Service
public class SessionService {
  private static final Duration SESSION_TTL = Duration.ofDays(30);

  private final JdbcTemplate database;
  private final boolean secureCookies;

  public SessionService(JdbcTemplate database,
      @Value("${app.secure-cookies:false}") boolean secureCookies) {
    this.database = database;
    this.secureCookies = secureCookies;
  }

  public IssuedSession issue(long creatorId) {
    String token = SessionCookies.newToken();
    OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(SESSION_TTL);
    database.update("insert into sessions(id,creator_id,expires_at) values(?,?,?)",
        SessionCookies.hash(token), creatorId, expiresAt);
    return new IssuedSession(token, expiresAt);
  }

  public void revoke(String token) {
    if (token != null) {
      database.update(
          "update sessions set revoked_at=current_timestamp where id=? and revoked_at is null",
          SessionCookies.hash(token));
    }
  }

  public ResponseCookie cookie(IssuedSession session, boolean requestIsSecure) {
    return ResponseCookie.from(SessionCookies.COOKIE_NAME, session.token())
        .httpOnly(true)
        .path("/")
        .sameSite("Lax")
        .maxAge(SESSION_TTL)
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
}
