package dev.creatorstore.service;

import dev.creatorstore.identity.IdentityRepository;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.FeatureRepository;
import dev.creatorstore.repository.StoreRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Keeps the account, mandatory profile, store defaults, and verification challenge atomic. */
@Service
public class RegistrationTransactionService {
  private final CreatorRepository creators;
  private final StoreRepository stores;
  private final FeatureRepository features;
  private final IdentityRepository identities;
  private final SessionService sessions;

  public RegistrationTransactionService(CreatorRepository creators, StoreRepository stores,
                                        FeatureRepository features,
                                        IdentityRepository identities,
                                        SessionService sessions) {
    this.creators = creators;
    this.stores = stores;
    this.features = features;
    this.identities = identities;
    this.sessions = sessions;
  }

  public boolean isHandleClaimed(String handle) {
    return creators.handleExists(handle);
  }

  @Transactional
  public CreatedAccount create(String handle, String displayName, String email, String phone,
                               String creatorLegacyVerifier, String verifier,
                               String transitionalLegacyVerifier, String accountStatus,
                               boolean emailVerified,
                               String verificationTokenHash,
                               OffsetDateTime verificationExpiresAt,
                               boolean issueSession, ClientContext context) {
    try {
      long accountId = creators.create(handle, displayName, email, phone, creatorLegacyVerifier);
      UUID publicId = identities.provision(accountId, handle, displayName, email, phone,
          creatorLegacyVerifier, verifier, transitionalLegacyVerifier, accountStatus,
          emailVerified);
      stores.createDefault(accountId, displayName + "'s Store");
      features.createNotificationDefaults(accountId);
      identities.createEmailVerificationToken(identities.primaryEmailId(accountId),
          verificationTokenHash, verificationExpiresAt);
      SessionService.IssuedSession session = issueSession
          ? sessions.issue(accountId, context, "password") : null;
      return new CreatedAccount(accountId, publicId, session);
    } catch (DataIntegrityViolationException conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Handle or email already exists.");
    }
  }

  public record CreatedAccount(long id, UUID publicId, SessionService.IssuedSession session) {}
}
