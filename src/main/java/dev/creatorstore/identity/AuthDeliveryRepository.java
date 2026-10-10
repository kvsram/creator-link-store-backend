package dev.creatorstore.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable queue for security-sensitive email side effects. */
@Repository
public class AuthDeliveryRepository {
  private static final int MAX_ATTEMPTS = 6;
  static final Duration LEASE = Duration.ofMinutes(2);
  private final JdbcTemplate database;

  public AuthDeliveryRepository(JdbcTemplate database) {
    this.database = database;
  }

  public UUID enqueue(Long accountId, String type, String recipient, String token,
                      OffsetDateTime expiresAt) {
    UUID deliveryId = UUID.randomUUID();
    database.update("insert into auth_delivery_outbox(delivery_id,account_id,delivery_type,"
            + "recipient,token_value,expires_at) values(?,?,?,?,?,?)",
        deliveryId, accountId, type, recipient, token, expiresAt);
    return deliveryId;
  }

  public Optional<Delivery> findForTest(UUID deliveryId) {
    List<Delivery> rows = database.query("select delivery_id,account_id,delivery_type,recipient,"
            + "token_value,expires_at,state,attempts,lease_token from auth_delivery_outbox "
            + "where delivery_id=? and expires_at>current_timestamp and token_value<>''",
        (rs, row) -> delivery(rs.getObject("delivery_id", UUID.class),
            rs.getObject("account_id", Long.class), rs.getString("delivery_type"),
            rs.getString("recipient"), rs.getString("token_value"),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getString("state"),
            rs.getInt("attempts"), rs.getObject("lease_token", UUID.class)), deliveryId);
    return rows.stream().findFirst();
  }

  @Transactional
  public Optional<Delivery> claim() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<Delivery> rows = database.query("select delivery_id,account_id,delivery_type,recipient,"
            + "token_value,expires_at,state,attempts,lease_token from auth_delivery_outbox "
            + "where expires_at>? and next_attempt_at<=? and "
            + "(state='pending' or (state='leased' and lease_until<?)) "
            + "order by created_at limit 1 for update skip locked",
        (rs, row) -> delivery(rs.getObject("delivery_id", UUID.class),
            rs.getObject("account_id", Long.class), rs.getString("delivery_type"),
            rs.getString("recipient"), rs.getString("token_value"),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getString("state"),
            rs.getInt("attempts"), rs.getObject("lease_token", UUID.class)), now, now, now);
    if (rows.isEmpty()) return Optional.empty();
    Delivery selected = rows.get(0);
    int nextAttempt = selected.attempts() + 1;
    UUID leaseToken = UUID.randomUUID();
    database.update("update auth_delivery_outbox set state='leased',attempts=?,lease_until=?,"
            + "lease_token=?,last_error=null where delivery_id=?", nextAttempt, now.plus(LEASE),
        leaseToken, selected.deliveryId());
    return Optional.of(new Delivery(selected.deliveryId(), selected.accountId(), selected.type(),
        selected.recipient(), selected.token(), selected.expiresAt(), "leased", nextAttempt,
        leaseToken));
  }

  public boolean markSent(Delivery delivery) {
    return database.update("update auth_delivery_outbox set state='sent',sent_at=current_timestamp,"
            + "lease_until=null,lease_token=null,last_error=null,token_value='' "
            + "where delivery_id=? and state='leased' and attempts=? and lease_token=?",
        delivery.deliveryId(), delivery.attempts(), delivery.leaseToken()) == 1;
  }

  public boolean markFailed(Delivery delivery, String error) {
    boolean dead = delivery.attempts() >= MAX_ATTEMPTS;
    Duration delay = Duration.ofSeconds(Math.min(900,
        15L * (1L << Math.min(6, Math.max(0, delivery.attempts() - 1)))));
    return database.update("update auth_delivery_outbox set state=?,next_attempt_at=?,"
            + "lease_until=null,lease_token=null,"
            + "last_error=?,token_value=case when ? then '' else token_value end "
            + "where delivery_id=? and state='leased' and attempts=? and lease_token=?",
        dead ? "dead" : "pending", OffsetDateTime.now(ZoneOffset.UTC).plus(delay),
        safeError(error), dead, delivery.deliveryId(), delivery.attempts(),
        delivery.leaseToken()) == 1;
  }

  /** Cancels unsent mail whose challenge has been replaced or consumed in the same transaction. */
  public int supersede(Long accountId, String type) {
    return database.update("update auth_delivery_outbox set state='dead',lease_until=null,"
            + "lease_token=null,last_error='superseded challenge',token_value='' "
            + "where account_id=? and delivery_type=? and state in ('pending','leased')",
        accountId, type);
  }

  public int expire() {
    return database.update("update auth_delivery_outbox set state='dead',lease_until=null,"
        + "lease_token=null,"
        + "last_error='expired before delivery',token_value='' where state in ('pending','leased') "
        + "and expires_at<=current_timestamp");
  }

  private static Delivery delivery(UUID id, Long accountId, String type, String recipient,
                                   String token, OffsetDateTime expiresAt, String state,
                                   int attempts, UUID leaseToken) {
    return new Delivery(id, accountId, type, recipient, token, expiresAt, state, attempts,
        leaseToken);
  }

  private static String safeError(String value) {
    if (value == null || value.isBlank()) return "mail delivery failed";
    String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
    return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
  }

  public record Delivery(UUID deliveryId, Long accountId, String type, String recipient,
                         String token, OffsetDateTime expiresAt, String state, int attempts,
                         UUID leaseToken) {}
}
