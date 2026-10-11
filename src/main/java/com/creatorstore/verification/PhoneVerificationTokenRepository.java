package com.creatorstore.verification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PhoneVerificationTokenRepository extends JpaRepository<PhoneVerificationToken, Long> {

    /** Closes every open link for this creator; called before a new one is issued. */
    @Modifying
    @Query("UPDATE PhoneVerificationToken t SET t.usedAt = :now WHERE t.creatorId = :creatorId AND t.usedAt IS NULL")
    int closeOpenTokens(@Param("creatorId") Long creatorId, @Param("now") Instant now);

    /** Row lock: two simultaneous clicks on the same link are serialized, so only the first succeeds. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PhoneVerificationToken> findByTokenHash(String tokenHash);

    /** SMS rate limit input. */
    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, Instant after);

    @Modifying
    @Query("DELETE FROM PhoneVerificationToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
