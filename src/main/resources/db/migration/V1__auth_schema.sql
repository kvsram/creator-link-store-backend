-- =====================================================================
-- V1: Authentication schema (creators, phone verification, sessions, admins)
--
-- Conventions used in every migration of this project:
--   * Money is never stored here. When it is (orders, prices), use BIGINT minor units.
--   * All timestamps are TIMESTAMPTZ and map to java.time.Instant.
--   * Enum-like columns store the Java enum name in UPPER_CASE, enforced by CHECK.
--   * Secrets (passwords, tokens) are stored only as hashes.
--   * Uniqueness is enforced only for real identifiers (email, username).
--     Names and phone numbers are NOT identities and are never unique.
--   * Once a migration has run anywhere outside a developer laptop,
--     it is never edited. Add V2, V3, ... instead.
-- =====================================================================

-- =========================================
-- 1. CREATORS (influencers who sell)
-- =========================================
CREATE TABLE creators (
    id                  BIGSERIAL    PRIMARY KEY,
    full_name           VARCHAR(100) NOT NULL,
    email               VARCHAR(254) NOT NULL,                 -- normalized in Java: trim + lowercase
    username            VARCHAR(30)  NOT NULL,                 -- normalized in Java: trim + lowercase
    password_hash       VARCHAR(100) NOT NULL,                 -- BCrypt
    phone_number        VARCHAR(20),                           -- optional, E.164 (+919876543210)
    phone_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified_at   TIMESTAMPTZ,

    -- login / account state
    account_status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Real identifiers: these are the ONLY uniqueness rules on this table.
    CONSTRAINT uq_creators_email    UNIQUE (email),
    CONSTRAINT uq_creators_username UNIQUE (username),

    -- Data-quality rules the database enforces even if application code has a bug.
    CONSTRAINT ck_creators_email_normalized CHECK (email = lower(email) AND email = btrim(email)),
    CONSTRAINT ck_creators_username_format  CHECK (username ~ '^[a-z0-9_.]{3,30}$'),
    CONSTRAINT ck_creators_phone_e164       CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_creators_account_status   CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'BANNED')),
    CONSTRAINT ck_creators_failed_logins    CHECK (failed_login_count >= 0),

    -- Verification state must be internally consistent.
    CONSTRAINT ck_creators_verified_state CHECK (
        (phone_verified = FALSE AND phone_verified_at IS NULL)
        OR (phone_verified = TRUE AND phone_number IS NOT NULL AND phone_verified_at IS NOT NULL)
    )
);

-- Admin lookup: "which accounts use this phone number?" (NOT unique on purpose)
CREATE INDEX idx_creators_phone
    ON creators (phone_number)
    WHERE phone_number IS NOT NULL;

-- Admin list screens filter by status
CREATE INDEX idx_creators_account_status
    ON creators (account_status);

-- =========================================
-- 2. PHONE VERIFICATION TOKENS (one row per link sent by SMS)
-- =========================================
CREATE TABLE phone_verification_tokens (
    id            BIGSERIAL    PRIMARY KEY,
    creator_id    BIGINT       NOT NULL REFERENCES creators (id) ON DELETE CASCADE,
    phone_number  VARCHAR(20)  NOT NULL,                       -- the number this link was sent to
    token_hash    VARCHAR(64)  NOT NULL,                       -- SHA-256 hex of the raw token
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,                                 -- set when used OR superseded by a newer link
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_pvt_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_pvt_expiry     CHECK (expires_at > created_at)
);

-- At most one open (unused, not superseded) link per creator
CREATE UNIQUE INDEX uq_pvt_one_open_per_creator
    ON phone_verification_tokens (creator_id)
    WHERE used_at IS NULL;

-- SMS rate limit: "how many links were sent to this number in the last hour?"
CREATE INDEX idx_pvt_phone_created
    ON phone_verification_tokens (phone_number, created_at);

-- Cleanup job deletes old rows by expiry
CREATE INDEX idx_pvt_expires
    ON phone_verification_tokens (expires_at);

-- =========================================
-- 3. REFRESH TOKENS (one row per creator session; rotated on every refresh)
-- =========================================
CREATE TABLE refresh_tokens (
    id              BIGSERIAL    PRIMARY KEY,
    creator_id      BIGINT       NOT NULL REFERENCES creators (id) ON DELETE CASCADE,
    family_id       UUID         NOT NULL,                     -- all tokens descended from one login
    token_hash      VARCHAR(64)  NOT NULL,                     -- SHA-256 hex of the raw token
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,                               -- logout, rotation, or reuse detection
    replaced_by_id  BIGINT       REFERENCES refresh_tokens (id),
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(45),                               -- fits IPv6
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,

    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_expiry     CHECK (expires_at > created_at)
);

-- Reuse detection revokes a whole family
CREATE INDEX idx_refresh_family
    ON refresh_tokens (family_id);

-- "Log out everywhere" / "my active sessions"
CREATE INDEX idx_refresh_creator_active
    ON refresh_tokens (creator_id)
    WHERE revoked_at IS NULL;

-- Cleanup job deletes expired rows
CREATE INDEX idx_refresh_expires
    ON refresh_tokens (expires_at);

-- =========================================
-- 4. ADMIN USERS (platform staff; separate from creators on purpose)
-- =========================================
CREATE TABLE admin_users (
    id                  BIGSERIAL    PRIMARY KEY,
    email               VARCHAR(254) NOT NULL,
    full_name           VARCHAR(100) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    role                VARCHAR(20)  NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_admin_users_email       UNIQUE (email),
    CONSTRAINT ck_admin_email_normalized  CHECK (email = lower(email) AND email = btrim(email)),
    CONSTRAINT ck_admin_role              CHECK (role IN ('SUPER_ADMIN', 'SUPPORT', 'FINANCE')),
    CONSTRAINT ck_admin_failed_logins     CHECK (failed_login_count >= 0)
);
