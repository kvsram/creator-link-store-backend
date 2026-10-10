package dev.creatorstore.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes append-only authentication audit events independently of the caller's transaction. */
@Service
public class SecurityEventService {
  private final IdentityRepository identities;

  public SecurityEventService(IdentityRepository identities) {
    this.identities = identities;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(Long accountId, String eventType, String outcome, String requestId,
                     String identifierHash, String ipHash, String userAgentHash) {
    identities.recordEvent(accountId, eventType, outcome, requestId, identifierHash, ipHash,
        userAgentHash);
  }
}
