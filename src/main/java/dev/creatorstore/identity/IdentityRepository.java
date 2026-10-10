package dev.creatorstore.identity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
  private final JdbcTemplate database;

  public IdentityRepository(JdbcTemplate database) {
    this.database = database;
  }

  public UUID provision(long accountId, String handle, String displayName, String email,
                        String phone, String creatorLegacyVerifier, String verifier,
                        String transitionalLegacyVerifier, String status,
                        boolean emailVerified) {
    UUID publicId = UUID.randomUUID();
    database.update("insert into accounts(id,public_id,status) values(?,?,?)",
        accountId, publicId, status);
    database.update("insert into creator_profiles(account_id,display_name,bio) values(?,?,?)",
        accountId, displayName, "");
    database.update("insert into creator_handles(handle,account_id,state) values(?,?,'active')",
        handle, accountId);
    database.update("insert into account_emails(account_id,email_original,email_normalized,"
            + "is_primary,verified_at) values(?,?,?,true,?)", accountId, email, email,
        emailVerified ? now() : null);
    if (phone != null) {
      database.update("insert into account_phones(account_id,phone_original,is_primary) "
          + "values(?,?,true)", accountId, phone);
    }
    database.update("insert into password_credentials(account_id,verifier,legacy_verifier) "
        + "values(?,?,?)", accountId, verifier, transitionalLegacyVerifier);
    database.update("insert into account_login_state(account_id) values(?)", accountId);
    // Keep the old credential usable during the dual-read rollback window.
    database.update("update creators set password_hash=? where id=?", creatorLegacyVerifier,
        accountId);
    return publicId;
  }

  public void provisionExisting(long accountId, String handle, String displayName, String email,
                                String phone, String verifier) {
    Integer count = database.queryForObject("select count(*) from accounts where id=?",
        Integer.class, accountId);
    if (count != null && count > 0) return;
    provision(accountId, handle, displayName, email, phone, verifier, verifier, null,
        "active", true);
  }

  public Optional<LoginAccount> findForLogin(String normalizedIdentifier) {
    String predicate;
    if (normalizedIdentifier.contains("@")) {
      predicate = "e.email_normalized=?";
    } else {
      predicate = "h.handle=?";
    }
    List<LoginAccount> rows = database.query("select a.id,a.public_id,a.status,a.session_version,"
            + "h.handle,p.display_name,p.bio,p.avatar_url,e.email_normalized,ph.phone_original,"
            + "pc.verifier,ls.locked_until "
            + "from accounts a join creator_profiles p on p.account_id=a.id "
            + "join creator_handles h on h.account_id=a.id and h.state='active' "
            + "join account_emails e on e.account_id=a.id and e.is_primary=true "
            + "left join account_phones ph on ph.account_id=a.id and ph.is_primary=true "
            + "join password_credentials pc on pc.account_id=a.id "
            + "join account_login_state ls on ls.account_id=a.id where " + predicate,
        (rs, row) -> new LoginAccount(rs.getLong("id"),
            UUID.fromString(rs.getString("public_id")), rs.getString("status"),
            rs.getInt("session_version"), rs.getString("handle"),
            rs.getString("display_name"), rs.getString("bio"), rs.getString("avatar_url"),
            rs.getString("email_normalized"), rs.getString("phone_original"),
            rs.getString("verifier"), rs.getObject("locked_until", OffsetDateTime.class)),
        normalizedIdentifier);
    return rows.stream().findFirst();
  }

  public Optional<AccountView> findAccount(long accountId) {
    List<AccountView> rows = database.query("select a.id,a.public_id,a.status,h.handle,"
            + "p.display_name,p.bio,coalesce(p.avatar_url,c.avatar_url) as avatar_url,"
            + "e.email_normalized,e.verified_at,ph.phone_original "
            + "from accounts a join creators c on c.id=a.id "
            + "join creator_profiles p on p.account_id=a.id "
            + "join creator_handles h on h.account_id=a.id and h.state='active' "
            + "join account_emails e on e.account_id=a.id and e.is_primary=true "
            + "left join account_phones ph on ph.account_id=a.id and ph.is_primary=true "
            + "where a.id=?",
        (rs, row) -> new AccountView(rs.getLong("id"),
            UUID.fromString(rs.getString("public_id")), rs.getString("status"),
            rs.getString("handle"), rs.getString("display_name"), rs.getString("bio"),
            rs.getString("avatar_url"), rs.getString("email_normalized"),
            rs.getObject("verified_at", OffsetDateTime.class), rs.getString("phone_original")),
        accountId);
    return rows.stream().findFirst();
  }

  public void recordFailure(long accountId, OffsetDateTime now, int threshold,
                            OffsetDateTime windowStart, OffsetDateTime lockedUntil) {
    database.update("update account_login_state set "
            + "consecutive_failures=case when failure_window_started_at is null "
            + "or failure_window_started_at<? then 1 else consecutive_failures+1 end,"
            + "failure_window_started_at=case when failure_window_started_at is null "
            + "or failure_window_started_at<? then ? else failure_window_started_at end,"
            + "last_failed_at=?,"
            + "locked_until=case when (case when failure_window_started_at is null "
            + "or failure_window_started_at<? then 1 else consecutive_failures+1 end)>=? "
            + "then ? else locked_until end,updated_at=? where account_id=?",
        windowStart, windowStart, now, now, windowStart, threshold, lockedUntil, now, accountId);
  }

  public void clearFailures(long accountId) {
    database.update("update account_login_state set consecutive_failures=0,"
        + "failure_window_started_at=null,last_failed_at=null,locked_until=null,"
        + "updated_at=current_timestamp where account_id=?", accountId);
  }

  public void upgradeVerifier(long accountId, String verifier) {
    database.update("update password_credentials set verifier=?,changed_at=current_timestamp "
        + "where account_id=?", verifier, accountId);
  }

  public long primaryEmailId(long accountId) {
    return database.queryForObject("select id from account_emails where account_id=? "
        + "and is_primary=true", Long.class, accountId);
  }

  public Optional<EmailAccount> findByEmail(String normalizedEmail) {
    List<EmailAccount> rows = database.query("select e.id as email_id,e.account_id,e.email_normalized,"
            + "e.verified_at,a.status from account_emails e join accounts a on a.id=e.account_id "
            + "where e.email_normalized=?",
        (rs, row) -> new EmailAccount(rs.getLong("email_id"), rs.getLong("account_id"),
            rs.getString("email_normalized"), rs.getObject("verified_at", OffsetDateTime.class),
            rs.getString("status")), normalizedEmail);
    return rows.stream().findFirst();
  }

  public void createEmailVerificationToken(long emailId, String tokenHash,
                                           OffsetDateTime expiresAt) {
    database.update("update email_verification_tokens set consumed_at=current_timestamp "
        + "where email_id=? and consumed_at is null", emailId);
    database.update("insert into email_verification_tokens(email_id,token_hash,expires_at) "
        + "values(?,?,?)", emailId, tokenHash, expiresAt);
  }

  public Optional<TokenAccount> findEmailVerificationForUpdate(String tokenHash) {
    List<TokenAccount> rows = database.query("select t.id,e.account_id from "
            + "email_verification_tokens t join account_emails e on e.id=t.email_id "
            + "join accounts a on a.id=e.account_id and a.status='pending_verification' "
            + "where t.token_hash=? and t.consumed_at is null and t.expires_at>current_timestamp "
            + "for update", (rs, row) -> new TokenAccount(rs.getLong("id"),
            rs.getLong("account_id")), tokenHash);
    return rows.stream().findFirst();
  }

  public void consumeEmailVerification(long tokenId, long accountId) {
    database.update("update email_verification_tokens set consumed_at=current_timestamp where id=?",
        tokenId);
    database.update("update account_emails set verified_at=current_timestamp where account_id=? "
        + "and is_primary=true", accountId);
    database.update("update creators set password_hash=(select legacy_verifier from "
            + "password_credentials where account_id=?) where id=? and exists "
            + "(select 1 from password_credentials where account_id=? and legacy_verifier "
            + "is not null)", accountId, accountId, accountId);
    database.update("update password_credentials set legacy_verifier=null where account_id=?",
        accountId);
    database.update("update accounts set status='active',updated_at=current_timestamp,"
        + "row_version=row_version+1 where id=? and status='pending_verification'", accountId);
  }

  public void createPasswordResetToken(long accountId, String tokenHash,
                                       OffsetDateTime expiresAt) {
    database.update("update password_reset_tokens set consumed_at=current_timestamp "
        + "where account_id=? and consumed_at is null", accountId);
    database.update("insert into password_reset_tokens(account_id,token_hash,expires_at) "
        + "values(?,?,?)", accountId, tokenHash, expiresAt);
  }

  public Optional<TokenAccount> findPasswordResetForUpdate(String tokenHash) {
    List<TokenAccount> rows = database.query("select t.id,t.account_id from password_reset_tokens t "
            + "join accounts a on a.id=t.account_id and a.status='active' "
            + "join account_emails e on e.account_id=a.id and e.is_primary=true "
            + "and e.verified_at is not null "
            + "where t.token_hash=? and t.consumed_at is null "
            + "and t.expires_at>current_timestamp for update",
        (rs, row) -> new TokenAccount(rs.getLong("id"), rs.getLong("account_id")), tokenHash);
    return rows.stream().findFirst();
  }

  public boolean hasEligiblePasswordReset(String tokenHash) {
    Integer count = database.queryForObject("select count(*) from password_reset_tokens t "
            + "join accounts a on a.id=t.account_id and a.status='active' "
            + "join account_emails e on e.account_id=a.id and e.is_primary=true "
            + "and e.verified_at is not null where t.token_hash=? and t.consumed_at is null "
            + "and t.expires_at>current_timestamp", Integer.class, tokenHash);
    return count != null && count > 0;
  }

  public void consumePasswordReset(long tokenId, long accountId, String verifier,
                                   String legacyVerifier) {
    database.update("update password_reset_tokens set consumed_at=current_timestamp where id=?",
        tokenId);
    database.update("update password_credentials set verifier=?,legacy_verifier=null,"
        + "changed_at=current_timestamp,must_change=false where account_id=?", verifier,
        accountId);
    database.update("update creators set password_hash=? where id=?", legacyVerifier, accountId);
    database.update("update accounts set session_version=session_version+1,"
        + "updated_at=current_timestamp,row_version=row_version+1 where id=?", accountId);
    database.update("update auth_sessions set revoked_at=current_timestamp,"
        + "revocation_reason='password_reset' where account_id=? and revoked_at is null", accountId);
    database.update("update sessions set revoked_at=current_timestamp where creator_id=? "
        + "and revoked_at is null", accountId);
    clearFailures(accountId);
  }

  public void recordEvent(Long accountId, String eventType, String outcome, String requestId,
                          String identifierHash, String ipHash, String userAgentHash) {
    database.update("insert into auth_security_events(account_id,event_type,outcome,request_id,"
            + "identifier_hash,ip_hmac,user_agent_hash) values(?,?,?,?,?,?,?)",
        accountId, eventType, outcome, requestId, identifierHash, ipHash, userAgentHash);
  }

  public OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public record LoginAccount(long id, UUID publicId, String status, int sessionVersion,
                             String handle, String displayName, String bio, String avatarUrl,
                             String email, String phone, String verifier,
                             OffsetDateTime lockedUntil) {}

  public record AccountView(long id, UUID publicId, String status, String handle,
                            String displayName, String bio, String avatarUrl, String email,
                            OffsetDateTime emailVerifiedAt, String phone) {}

  public record EmailAccount(long emailId, long accountId, String email,
                             OffsetDateTime verifiedAt, String status) {}

  public record TokenAccount(long tokenId, long accountId) {}
}
