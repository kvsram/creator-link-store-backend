package com.creatorstore.creator;

import java.time.Instant;

/** What a creator sees about their own account. Never includes the password hash or login counters. */
public record CreatorProfileResponse(
        Long id,
        String fullName,
        String email,
        String username,
        String phoneNumber,
        boolean phoneVerified,
        AccountStatus accountStatus,
        Instant createdAt
) {
    public static CreatorProfileResponse from(Creator c) {
        return new CreatorProfileResponse(
                c.getId(), c.getFullName(), c.getEmail(), c.getUsername(),
                c.getPhoneNumber(), c.isPhoneVerified(), c.getAccountStatus(), c.getCreatedAt());
    }
}
