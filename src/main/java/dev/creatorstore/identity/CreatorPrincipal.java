package dev.creatorstore.identity;

import java.util.UUID;

/** Authenticated creator identity placed in Spring Security's SecurityContext. */
public record CreatorPrincipal(long accountId, UUID sessionId) {}
