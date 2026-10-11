-- =====================================================================
-- PLANNED SCHEMA for the modules that are not yet implemented.
--
-- This file is NOT run by Flyway. It is the agreed starting point for the
-- future migrations named below. When you implement a module, copy its
-- section into src/main/resources/db/migration/V<n>__<name>.sql, adjust,
-- run it, and delete the section here.
--
-- Conventions: see HANDOFF.md section 4 ("Database rules").
-- Enum columns store Java enum NAMES; money is BIGINT minor units + CHAR(3).
--
-- Verified: every section below was applied to PostgreSQL 16 on top of V1
-- in this order: V2, V3, V4, V5, V7, V6, V8, V9, V10, V11, V12.
-- (V6 references customers, so number the customers migration lower than
-- the automation one when you create the real files, e.g. V6__customers / V7__automation.)
-- =====================================================================


-- =====================================================================
-- V2__products.sql                                   (module: product)
-- =====================================================================
CREATE TABLE products (
    id              BIGSERIAL    PRIMARY KEY,
    creator_id      BIGINT       NOT NULL REFERENCES creators (id) ON DELETE CASCADE,
    title           VARCHAR(150) NOT NULL,
    description     TEXT,
    image_url       TEXT,
    product_type    VARCHAR(20)  NOT NULL,     -- DIGITAL | COURSE | PHYSICAL | BOOKING
    review_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | APPROVED | REJECTED (admin)
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',     -- DRAFT | ACTIVE | HIDDEN | ARCHIVED (creator)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_products_type          CHECK (product_type IN ('DIGITAL', 'COURSE', 'PHYSICAL', 'BOOKING')),
    CONSTRAINT ck_products_review_status CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_products_status        CHECK (status IN ('DRAFT', 'ACTIVE', 'HIDDEN', 'ARCHIVED'))
);
CREATE INDEX idx_products_creator ON products (creator_id, status);

CREATE TABLE prices (
    id              BIGSERIAL    PRIMARY KEY,
    product_id      BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    valid_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prices_amount   CHECK (amount_minor >= 0),
    CONSTRAINT ck_prices_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_prices_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX idx_prices_product_active ON prices (product_id) WHERE is_active = TRUE;


-- =====================================================================
-- V3__bookings.sql                                   (module: booking)
-- =====================================================================
CREATE TABLE booking_sessions (
    id                  BIGSERIAL   PRIMARY KEY,
    product_id          BIGINT      NOT NULL UNIQUE REFERENCES products (id) ON DELETE CASCADE,
    duration_minutes    INT         NOT NULL,
    timezone            VARCHAR(60) NOT NULL,          -- IANA, e.g. Asia/Kolkata
    capacity            INT         NOT NULL DEFAULT 1,
    CONSTRAINT ck_bs_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_bs_capacity CHECK (capacity > 0)
);

CREATE TABLE booking_slots (
    id                  BIGSERIAL   PRIMARY KEY,
    booking_session_id  BIGINT      NOT NULL REFERENCES booking_sessions (id) ON DELETE CASCADE,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    booked_count        INT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_slot_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_slot_count  CHECK (booked_count >= 0),
    CONSTRAINT uq_slot_start  UNIQUE (booking_session_id, starts_at)
);
CREATE INDEX idx_slots_session_time ON booking_slots (booking_session_id, starts_at);
-- Rule enforced in BookingService with SELECT ... FOR UPDATE on the slot: booked_count <= capacity.


-- =====================================================================
-- V4__store_pages.sql                                (module: storefront)
-- =====================================================================
CREATE TABLE store_pages (
    id              BIGSERIAL    PRIMARY KEY,
    creator_id      BIGINT       NOT NULL UNIQUE REFERENCES creators (id) ON DELETE CASCADE,
    slug            VARCHAR(30)  NOT NULL UNIQUE,       -- usually = creators.username
    display_name    VARCHAR(100) NOT NULL,
    bio             VARCHAR(500),
    theme           VARCHAR(30)  NOT NULL DEFAULT 'DEFAULT',
    is_published    BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_store_slug CHECK (slug ~ '^[a-z0-9_.]{3,30}$')
);
-- Publish rule (service): creator ACTIVE AND phone_verified AND at least one ACTIVE+APPROVED product.


-- =====================================================================
-- V5__social_accounts.sql                            (module: automation)
-- =====================================================================
CREATE TABLE social_accounts (
    id                      BIGSERIAL    PRIMARY KEY,
    creator_id              BIGINT       NOT NULL REFERENCES creators (id) ON DELETE CASCADE,
    platform                VARCHAR(20)  NOT NULL DEFAULT 'INSTAGRAM',
    platform_account_id     VARCHAR(100) NOT NULL,
    platform_username       VARCHAR(100),
    account_type            VARCHAR(20),                 -- BUSINESS | CREATOR
    access_token_encrypted  TEXT         NOT NULL,       -- AES-GCM in the application; key from env
    token_expires_at        TIMESTAMPTZ,
    connection_status       VARCHAR(20)  NOT NULL DEFAULT 'CONNECTED',  -- CONNECTED | EXPIRED | REVOKED | RESTRICTED
    connected_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_social_platform_account UNIQUE (platform, platform_account_id),   -- real identifier
    CONSTRAINT ck_social_status CHECK (connection_status IN ('CONNECTED', 'EXPIRED', 'REVOKED', 'RESTRICTED'))
);
CREATE INDEX idx_social_creator ON social_accounts (creator_id);


-- =====================================================================
-- V6__automation.sql                                 (module: automation)
-- =====================================================================
CREATE TABLE automation_rules (
    id                  BIGSERIAL    PRIMARY KEY,
    creator_id          BIGINT       NOT NULL REFERENCES creators (id) ON DELETE CASCADE,
    social_account_id   BIGINT       NOT NULL REFERENCES social_accounts (id) ON DELETE CASCADE,
    product_id          BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    trigger_keyword     VARCHAR(50)  NOT NULL,           -- stored lowercase, matched case-insensitively
    trigger_source      VARCHAR(10)  NOT NULL,           -- COMMENT | DM | BOTH
    source_post_id      VARCHAR(100),                    -- NULL = any post
    reply_template      TEXT         NOT NULL,           -- must contain {link}
    is_active           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rule_source  CHECK (trigger_source IN ('COMMENT', 'DM', 'BOTH')),
    CONSTRAINT ck_rule_keyword CHECK (trigger_keyword = lower(trigger_keyword))
);
CREATE INDEX idx_rules_account_active ON automation_rules (social_account_id) WHERE is_active = TRUE;

-- (customers table is V7; create V7 before V6 if you keep these FKs, or move dm_logs/tracking_links to V7.)
CREATE TABLE dm_logs (
    id                  BIGSERIAL    PRIMARY KEY,
    automation_rule_id  BIGINT       NOT NULL REFERENCES automation_rules (id),
    creator_id          BIGINT       NOT NULL REFERENCES creators (id),
    customer_id         BIGINT       NOT NULL REFERENCES customers (id),
    platform_event_id   VARCHAR(150) NOT NULL,           -- Meta comment/message id: idempotency key for webhooks
    status              VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',   -- QUEUED | SENT | FAILED | CANCELLED
    attempts            INT          NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    error_code          VARCHAR(50),
    error_message       TEXT,
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_dm_event  UNIQUE (platform_event_id),
    CONSTRAINT ck_dm_status CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'CANCELLED'))
);
CREATE INDEX idx_dm_queue   ON dm_logs (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX idx_dm_creator ON dm_logs (creator_id, created_at);

CREATE TABLE tracking_links (
    id              BIGSERIAL    PRIMARY KEY,
    token           VARCHAR(32)  NOT NULL UNIQUE,        -- short, URL-safe, random; appears in the DM
    creator_id      BIGINT       NOT NULL REFERENCES creators (id),
    product_id      BIGINT       NOT NULL REFERENCES products (id),
    customer_id     BIGINT       REFERENCES customers (id),
    dm_log_id       BIGINT       REFERENCES dm_logs (id),
    click_count     INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tracking_creator ON tracking_links (creator_id, created_at);


-- =====================================================================
-- V7__customers.sql                                  (module: customer)
-- =====================================================================
CREATE TABLE customers (
    id                  BIGSERIAL    PRIMARY KEY,
    platform            VARCHAR(20)  NOT NULL DEFAULT 'INSTAGRAM',
    platform_user_id    VARCHAR(100) NOT NULL,
    platform_username   VARCHAR(100),
    email               VARCHAR(254),                    -- captured at checkout, normalized
    first_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_platform_user UNIQUE (platform, platform_user_id),   -- real identifier
    CONSTRAINT ck_customer_email CHECK (email IS NULL OR email = lower(email))
);
CREATE INDEX idx_customers_email ON customers (email) WHERE email IS NOT NULL;


-- =====================================================================
-- V8__orders.sql                                     (module: order)
-- =====================================================================
CREATE TABLE orders (
    id                  BIGSERIAL    PRIMARY KEY,
    creator_id          BIGINT       NOT NULL REFERENCES creators (id),
    customer_id         BIGINT       NOT NULL REFERENCES customers (id),
    product_id          BIGINT       NOT NULL REFERENCES products (id),
    price_id            BIGINT       NOT NULL REFERENCES prices (id),
    tracking_link_id    BIGINT       REFERENCES tracking_links (id),
    booking_slot_id     BIGINT       REFERENCES booking_slots (id),
    amount_minor        BIGINT       NOT NULL,           -- snapshot at purchase time
    currency            CHAR(3)      NOT NULL,
    platform_fee_minor  BIGINT       NOT NULL DEFAULT 0,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    paid_at             TIMESTAMPTZ,
    CONSTRAINT ck_order_amount CHECK (amount_minor >= 0 AND platform_fee_minor >= 0 AND platform_fee_minor <= amount_minor),
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'DISPUTED'))
);
CREATE INDEX idx_orders_creator_date ON orders (creator_id, created_at);
CREATE INDEX idx_orders_customer     ON orders (customer_id);
CREATE INDEX idx_orders_status       ON orders (status) WHERE status IN ('PENDING', 'DISPUTED');

CREATE TABLE fulfillments (
    id                  BIGSERIAL    PRIMARY KEY,
    order_id            BIGINT       NOT NULL UNIQUE REFERENCES orders (id),
    delivery_type       VARCHAR(20)  NOT NULL,           -- FILE | BOOKING | PHYSICAL | ACCESS
    file_key            TEXT,                            -- object-storage key; signed URLs are generated on demand
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | DELIVERED | FAILED
    attempts            INT          NOT NULL DEFAULT 0,
    last_error          TEXT,
    delivered_at        TIMESTAMPTZ,
    access_expires_at   TIMESTAMPTZ,
    CONSTRAINT ck_fulfil_type   CHECK (delivery_type IN ('FILE', 'BOOKING', 'PHYSICAL', 'ACCESS')),
    CONSTRAINT ck_fulfil_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
);
CREATE INDEX idx_fulfil_pending ON fulfillments (id) WHERE status = 'PENDING';


-- =====================================================================
-- V9__payments.sql                                   (module: payment)
-- =====================================================================
CREATE TABLE payments (
    id                      BIGSERIAL    PRIMARY KEY,
    order_id                BIGINT       NOT NULL REFERENCES orders (id),
    provider                VARCHAR(20)  NOT NULL,       -- STRIPE | RAZORPAY
    provider_payment_id     VARCHAR(100) NOT NULL,
    amount_minor            BIGINT       NOT NULL,
    currency                CHAR(3)      NOT NULL,
    status                  VARCHAR(20)  NOT NULL,       -- PENDING | SUCCEEDED | FAILED
    failure_code            VARCHAR(50),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    paid_at                 TIMESTAMPTZ,
    CONSTRAINT uq_payment_provider_id UNIQUE (provider, provider_payment_id),   -- webhook idempotency
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX idx_payments_order ON payments (order_id);

CREATE TABLE refunds (
    id                  BIGSERIAL    PRIMARY KEY,
    order_id            BIGINT       NOT NULL REFERENCES orders (id),
    payment_id          BIGINT       NOT NULL REFERENCES payments (id),
    admin_id            BIGINT       REFERENCES admin_users (id),      -- NULL when provider-initiated
    provider_refund_id  VARCHAR(100),
    amount_minor        BIGINT       NOT NULL,
    reason              TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',       -- PENDING | SUCCEEDED | FAILED
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_refund_provider_id UNIQUE (provider_refund_id),
    CONSTRAINT ck_refund_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX idx_refunds_order ON refunds (order_id);

CREATE TABLE disputes (
    id                  BIGSERIAL    PRIMARY KEY,
    order_id            BIGINT       NOT NULL REFERENCES orders (id),
    payment_id          BIGINT       NOT NULL REFERENCES payments (id),
    provider_dispute_id VARCHAR(100) NOT NULL UNIQUE,
    amount_minor        BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',          -- OPEN | EVIDENCE_SUBMITTED | WON | LOST
    response_due_at     TIMESTAMPTZ,
    opened_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,
    CONSTRAINT ck_dispute_status CHECK (status IN ('OPEN', 'EVIDENCE_SUBMITTED', 'WON', 'LOST'))
);
CREATE INDEX idx_disputes_open ON disputes (response_due_at) WHERE status = 'OPEN';


-- =====================================================================
-- V10__payouts.sql                                   (module: payment)
-- =====================================================================
CREATE TABLE payout_accounts (
    id                  BIGSERIAL    PRIMARY KEY,
    creator_id          BIGINT       NOT NULL UNIQUE REFERENCES creators (id) ON DELETE CASCADE,
    provider            VARCHAR(20)  NOT NULL,           -- STRIPE_CONNECT | RAZORPAY_ROUTE | MANUAL
    provider_account_id VARCHAR(100),                    -- never raw bank numbers here
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | VERIFIED | REJECTED
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_payout_acct_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE TABLE payout_batches (
    id              BIGSERIAL    PRIMARY KEY,
    created_by      BIGINT       NOT NULL REFERENCES admin_users (id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',   -- DRAFT | PROCESSING | COMPLETED | FAILED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT ck_batch_status CHECK (status IN ('DRAFT', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE creator_payouts (
    id                  BIGSERIAL    PRIMARY KEY,
    payout_batch_id     BIGINT       NOT NULL REFERENCES payout_batches (id),
    creator_id          BIGINT       NOT NULL REFERENCES creators (id),
    period_start        DATE         NOT NULL,
    period_end          DATE         NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,
    provider_payout_id  VARCHAR(100),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | SENT | FAILED
    failure_reason      TEXT,
    sent_at             TIMESTAMPTZ,
    CONSTRAINT uq_payout_creator_period UNIQUE (creator_id, period_start, period_end),   -- never pay twice
    CONSTRAINT ck_payout_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_payout_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX idx_payouts_batch ON creator_payouts (payout_batch_id);


-- =====================================================================
-- V11__creator_subscriptions.sql                     (module: payment)
-- =====================================================================
CREATE TABLE creator_subscriptions (
    id                      BIGSERIAL    PRIMARY KEY,
    creator_id              BIGINT       NOT NULL UNIQUE REFERENCES creators (id) ON DELETE CASCADE,
    plan                    VARCHAR(20)  NOT NULL,       -- CREATOR | CREATOR_PRO
    status                  VARCHAR(20)  NOT NULL,       -- TRIALING | ACTIVE | PAST_DUE | CANCELLED
    provider_subscription_id VARCHAR(100) UNIQUE,
    current_period_end      TIMESTAMPTZ  NOT NULL,
    cancelled_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sub_plan   CHECK (plan IN ('CREATOR', 'CREATOR_PRO')),
    CONSTRAINT ck_sub_status CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELLED'))
);
CREATE INDEX idx_subs_period_end ON creator_subscriptions (current_period_end) WHERE status IN ('ACTIVE', 'TRIALING');


-- =====================================================================
-- V12__support.sql                                   (module: support)
-- =====================================================================
CREATE TABLE support_tickets (
    id                  BIGSERIAL    PRIMARY KEY,
    requester_type      VARCHAR(10)  NOT NULL,           -- CREATOR | CUSTOMER
    creator_id          BIGINT       REFERENCES creators (id),
    customer_id         BIGINT       REFERENCES customers (id),
    order_id            BIGINT       REFERENCES orders (id),
    category            VARCHAR(20)  NOT NULL,           -- PAYMENT | DELIVERY | INSTAGRAM | PRODUCT | ACCOUNT | OTHER
    priority            VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',   -- LOW | NORMAL | HIGH | URGENT
    status              VARCHAR(10)  NOT NULL DEFAULT 'OPEN',     -- OPEN | WAITING | RESOLVED
    assigned_admin_id   BIGINT       REFERENCES admin_users (id),
    subject             VARCHAR(200) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    CONSTRAINT ck_ticket_requester CHECK (
        (requester_type = 'CREATOR'  AND creator_id  IS NOT NULL) OR
        (requester_type = 'CUSTOMER' AND customer_id IS NOT NULL)),
    CONSTRAINT ck_ticket_category CHECK (category IN ('PAYMENT', 'DELIVERY', 'INSTAGRAM', 'PRODUCT', 'ACCOUNT', 'OTHER')),
    CONSTRAINT ck_ticket_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_ticket_status   CHECK (status IN ('OPEN', 'WAITING', 'RESOLVED'))
);
CREATE INDEX idx_tickets_open ON support_tickets (priority, created_at) WHERE status <> 'RESOLVED';

CREATE TABLE support_messages (
    id                  BIGSERIAL    PRIMARY KEY,
    ticket_id           BIGINT       NOT NULL REFERENCES support_tickets (id) ON DELETE CASCADE,
    author_type         VARCHAR(10)  NOT NULL,           -- CREATOR | CUSTOMER | ADMIN
    author_admin_id     BIGINT       REFERENCES admin_users (id),
    body                TEXT         NOT NULL,
    is_internal_note    BOOLEAN      NOT NULL DEFAULT FALSE,   -- admin-only; never returned to requesters
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_msg_author CHECK (author_type IN ('CREATOR', 'CUSTOMER', 'ADMIN'))
);
CREATE INDEX idx_messages_ticket ON support_messages (ticket_id, created_at);

CREATE TABLE system_errors (
    id              BIGSERIAL    PRIMARY KEY,
    source          VARCHAR(20)  NOT NULL,               -- META_API | WEBHOOK | PAYMENT | DELIVERY | SMS
    creator_id      BIGINT       REFERENCES creators (id),
    error_code      VARCHAR(50),
    message         TEXT         NOT NULL,
    context_json    JSONB,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);
CREATE INDEX idx_errors_unresolved ON system_errors (occurred_at) WHERE resolved_at IS NULL;

-- Append-only. Every admin action writes here IN THE SAME TRANSACTION as the action.
CREATE TABLE audit_logs (
    id              BIGSERIAL    PRIMARY KEY,
    admin_id        BIGINT       REFERENCES admin_users (id),
    action          VARCHAR(60)  NOT NULL,               -- e.g. CREATOR_SUSPEND, PAYOUT_BATCH_CREATE
    target_type     VARCHAR(30)  NOT NULL,
    target_id       BIGINT       NOT NULL,
    details_json    JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_target ON audit_logs (target_type, target_id, created_at);
CREATE INDEX idx_audit_admin  ON audit_logs (admin_id, created_at);

-- Suspension history (current state lives in creators.account_status; keep both in sync in one tx)
CREATE TABLE creator_status_changes (
    id              BIGSERIAL    PRIMARY KEY,
    creator_id      BIGINT       NOT NULL REFERENCES creators (id) ON DELETE CASCADE,
    admin_id        BIGINT       NOT NULL REFERENCES admin_users (id),
    from_status     VARCHAR(20)  NOT NULL,
    to_status       VARCHAR(20)  NOT NULL,
    reason_code     VARCHAR(40)  NOT NULL,               -- CHARGEBACK_THRESHOLD | SPAM | POLICY_VIOLATION | UNPAID | FRAUD | APPEAL_ACCEPTED
    reason_text     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_changes_creator ON creator_status_changes (creator_id, created_at);
