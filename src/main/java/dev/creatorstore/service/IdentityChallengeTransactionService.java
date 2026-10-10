package dev.creatorstore.service;

import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.AuthDeliveryService;
import dev.creatorstore.identity.SessionService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Owns the lock-and-consume transactions for one-time identity challenges. */
@Service
public class IdentityChallengeTransactionService {
  private final IdentityRepository identities;
  private final SessionService sessions;
  private final AuthDeliveryService deliveries;

  public IdentityChallengeTransactionService(IdentityRepository identities,
                                             SessionService sessions,
                                             AuthDeliveryService deliveries) {
    this.identities = identities;
    this.sessions = sessions;
    this.deliveries = deliveries;
  }

  @Transactional
  public VerifiedEmail verifyEmail(String tokenHash, ClientContext context) {
    var challenge = identities.findEmailVerificationForUpdate(tokenHash)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Verification token is invalid or expired."));
    identities.consumeEmailVerification(challenge.accountId());
    deliveries.supersede(challenge.accountId(), "email_verification");
    SessionService.IssuedSession session = sessions.issue(challenge.accountId(), context,
        "email_verification");
    return new VerifiedEmail(challenge.accountId(), session);
  }

  @Transactional
  public Optional<ChallengeDelivery> createEmailVerification(String normalizedEmail,
                                                              String rawToken, String tokenHash,
                                                              OffsetDateTime expiry) {
    Optional<IdentityRepository.EmailAccount> eligible =
        identities.findByEmailForUpdate(normalizedEmail)
        .filter(value -> value.verifiedAt() == null
            && "pending_verification".equals(value.status()));
    return eligible.map(value -> {
      deliveries.supersede(value.accountId(), "email_verification");
      identities.createEmailVerificationToken(value.emailId(), tokenHash, expiry);
      UUID deliveryId = deliveries.enqueue(value.accountId(), "email_verification",
          value.email(), rawToken, expiry);
      return new ChallengeDelivery(value.accountId(), deliveryId);
    });
  }

  @Transactional
  public Optional<ChallengeDelivery> createPasswordReset(String normalizedEmail, String rawToken,
                                                         String tokenHash,
                                                         OffsetDateTime expiry) {
    Optional<IdentityRepository.EmailAccount> eligible =
        identities.findByEmailForUpdate(normalizedEmail)
        .filter(value -> "active".equals(value.status()) && value.verifiedAt() != null);
    return eligible.map(value -> {
      deliveries.supersede(value.accountId(), "password_reset");
      identities.createPasswordResetToken(value.accountId(), tokenHash, expiry);
      UUID deliveryId = deliveries.enqueue(value.accountId(), "password_reset", value.email(),
          rawToken, expiry);
      return new ChallengeDelivery(value.accountId(), deliveryId);
    });
  }

  @Transactional
  public long resetPassword(String tokenHash, String verifier, String legacyVerifier) {
    var challenge = identities.findPasswordResetForUpdate(tokenHash)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Password-reset token is invalid or expired."));
    identities.consumePasswordReset(challenge.accountId(), verifier, legacyVerifier);
    deliveries.supersede(challenge.accountId(), "password_reset");
    return challenge.accountId();
  }

  public boolean hasEligiblePasswordReset(String tokenHash) {
    return identities.hasEligiblePasswordReset(tokenHash);
  }

  public record VerifiedEmail(long accountId, SessionService.IssuedSession session) {}
  public record ChallengeDelivery(long accountId, UUID deliveryId) {}
}
