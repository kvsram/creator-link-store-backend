package dev.creatorstore.service;

import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.SessionService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Owns the lock-and-consume transactions for one-time identity challenges. */
@Service
public class IdentityChallengeTransactionService {
  private final IdentityRepository identities;
  private final SessionService sessions;

  public IdentityChallengeTransactionService(IdentityRepository identities,
                                             SessionService sessions) {
    this.identities = identities;
    this.sessions = sessions;
  }

  @Transactional
  public VerifiedEmail verifyEmail(String tokenHash, ClientContext context) {
    var challenge = identities.findEmailVerificationForUpdate(tokenHash)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Verification token is invalid or expired."));
    identities.consumeEmailVerification(challenge.accountId());
    SessionService.IssuedSession session = sessions.issue(challenge.accountId(), context,
        "email_verification");
    return new VerifiedEmail(challenge.accountId(), session);
  }

  @Transactional
  public Optional<Long> createEmailVerification(String normalizedEmail, String tokenHash,
                                                 OffsetDateTime expiry) {
    Optional<IdentityRepository.EmailAccount> eligible =
        identities.findByEmailForUpdate(normalizedEmail)
        .filter(value -> value.verifiedAt() == null
            && "pending_verification".equals(value.status()));
    eligible.ifPresent(value -> identities.createEmailVerificationToken(value.emailId(),
        tokenHash, expiry));
    return eligible.map(IdentityRepository.EmailAccount::accountId);
  }

  @Transactional
  public Optional<Long> createPasswordReset(String normalizedEmail, String tokenHash,
                                             OffsetDateTime expiry) {
    Optional<IdentityRepository.EmailAccount> eligible =
        identities.findByEmailForUpdate(normalizedEmail)
        .filter(value -> "active".equals(value.status()) && value.verifiedAt() != null);
    eligible.ifPresent(value -> identities.createPasswordResetToken(value.accountId(), tokenHash,
        expiry));
    return eligible.map(IdentityRepository.EmailAccount::accountId);
  }

  @Transactional
  public long resetPassword(String tokenHash, String verifier, String legacyVerifier) {
    var challenge = identities.findPasswordResetForUpdate(tokenHash)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Password-reset token is invalid or expired."));
    identities.consumePasswordReset(challenge.accountId(), verifier, legacyVerifier);
    return challenge.accountId();
  }

  public boolean hasEligiblePasswordReset(String tokenHash) {
    return identities.hasEligiblePasswordReset(tokenHash);
  }

  public record VerifiedEmail(long accountId, SessionService.IssuedSession session) {}
}
