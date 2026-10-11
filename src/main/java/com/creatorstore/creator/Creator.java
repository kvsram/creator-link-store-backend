package com.creatorstore.creator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Maps to the {@code creators} table (V1__auth_schema.sql).
 *
 * All fields are written through intention-revealing methods, never through setters,
 * so the invariants of the table (for example: verified implies a phone number) stay in one place.
 */
@Entity
@Table(name = "creators")
public class Creator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 30)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Creator() {
        // JPA
    }

    public Creator(String fullName, String email, String username, String passwordHash, String phoneNumber) {
        Instant now = Instant.now();
        this.fullName = fullName;
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
        this.phoneNumber = phoneNumber;
        this.phoneVerified = false;
        this.accountStatus = AccountStatus.ACTIVE;
        this.failedLoginCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ---- behaviour -------------------------------------------------------

    public void markPhoneVerified(Instant now) {
        if (phoneNumber == null) {
            throw new IllegalStateException("Cannot verify a creator without a phone number");
        }
        this.phoneVerified = true;
        this.phoneVerifiedAt = now;
    }

    /** Changing the number always resets verification (the CHECK constraint requires this). */
    public void changePhoneNumber(String newPhoneNumber) {
        this.phoneNumber = newPhoneNumber;
        this.phoneVerified = false;
        this.phoneVerifiedAt = null;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean canLogin() {
        return accountStatus == AccountStatus.ACTIVE;
    }

    // ---- getters ---------------------------------------------------------

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getPhoneNumber() { return phoneNumber; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public Instant getPhoneVerifiedAt() { return phoneVerifiedAt; }
    public AccountStatus getAccountStatus() { return accountStatus; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
