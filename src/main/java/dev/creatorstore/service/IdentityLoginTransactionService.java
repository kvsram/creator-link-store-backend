package dev.creatorstore.service;

import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.PasswordService;
import dev.creatorstore.identity.SessionService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the final credential decision, upgrade, and session issue against password reset. */
@Service
public class IdentityLoginTransactionService {
  private final IdentityRepository identities;
  private final PasswordService passwords;
  private final SessionService sessions;

  public IdentityLoginTransactionService(IdentityRepository identities, PasswordService passwords,
                                         SessionService sessions) {
    this.identities = identities;
    this.passwords = passwords;
    this.sessions = sessions;
  }

  @Transactional
  public Result finalizeLogin(long accountId, String previouslyVerifiedVerifier,
                              String rawPassword, String upgradeCandidate,
                              ClientContext context) {
    var current = identities.findForLoginByAccountIdForUpdate(accountId).orElse(null);
    if (current == null) return Result.invalid();

    boolean sameCredential = current.verifier().equals(previouslyVerifiedVerifier);
    if (!sameCredential && !passwords.matches(rawPassword, current.verifier())) {
      return Result.invalid();
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if (current.lockedUntil() != null && current.lockedUntil().isAfter(now)
        || "suspended".equals(current.status()) || "deleted".equals(current.status())) {
      return Result.invalid();
    }
    if ("pending_verification".equals(current.status())) {
      return Result.emailNotVerified();
    }
    if (!"active".equals(current.status())) return Result.invalid();

    identities.clearFailures(accountId);
    if (passwords.needsUpgrade(current.verifier()) && upgradeCandidate != null) {
      identities.upgradeVerifier(accountId, upgradeCandidate);
    }
    return Result.success(sessions.issue(accountId, context, "password"));
  }

  public enum Outcome { SUCCESS, INVALID, EMAIL_NOT_VERIFIED }

  public record Result(Outcome outcome, SessionService.IssuedSession session) {
    static Result success(SessionService.IssuedSession session) {
      return new Result(Outcome.SUCCESS, session);
    }

    static Result invalid() {
      return new Result(Outcome.INVALID, null);
    }

    static Result emailNotVerified() {
      return new Result(Outcome.EMAIL_NOT_VERIFIED, null);
    }
  }
}
