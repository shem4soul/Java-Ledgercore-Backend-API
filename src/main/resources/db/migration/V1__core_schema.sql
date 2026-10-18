-- LedgerCore core schema. All money is stored as integer minor units (kobo/cents). Never floating point.

CREATE TABLE users (
    id              uuid PRIMARY KEY,
    email           varchar(255) NOT NULL,
    password_hash   varchar(100) NOT NULL,
    full_name       varchar(150) NOT NULL,
    enabled         boolean      NOT NULL DEFAULT true,
    failed_attempts integer      NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE user_roles (
    user_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    varchar(20) NOT NULL CHECK (role IN ('CUSTOMER', 'SUPPORT', 'AUDITOR', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  char(64)    NOT NULL,
    family_id   uuid        NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_refresh_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_family ON refresh_tokens (family_id);

CREATE SEQUENCE account_number_seq START 1000000001;

CREATE TABLE accounts (
    id                  uuid PRIMARY KEY,
    account_number      varchar(10) NOT NULL UNIQUE,
    owner_id            uuid REFERENCES users (id),
    type                varchar(10) NOT NULL CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    currency            char(3)     NOT NULL CHECK (currency IN ('NGN', 'USD', 'GBP', 'EUR')),
    status              varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    balance_minor       bigint      NOT NULL DEFAULT 0,
    daily_limit_minor   bigint      NOT NULL CHECK (daily_limit_minor >= 0),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,
    -- last line of defence: even a bug in the application can never overdraw a customer account
    CONSTRAINT ck_customer_not_overdrawn CHECK (type = 'SYSTEM' OR balance_minor >= 0),
    CONSTRAINT ck_customer_has_owner     CHECK (type = 'SYSTEM' OR owner_id IS NOT NULL)
);
CREATE INDEX ix_accounts_owner ON accounts (owner_id);

CREATE TABLE ledger_transactions (
    id             uuid PRIMARY KEY,
    reference      varchar(30)  NOT NULL UNIQUE,
    type           varchar(12)  NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL')),
    status         varchar(10)  NOT NULL CHECK (status IN ('POSTED', 'REVERSED')),
    amount_minor   bigint       NOT NULL CHECK (amount_minor > 0),
    currency       char(3)      NOT NULL,
    description    varchar(255),
    created_by     uuid,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    reversal_of    uuid UNIQUE REFERENCES ledger_transactions (id),   -- a transaction can be reversed at most once
    version        bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_ledger_tx_created ON ledger_transactions (created_at DESC);

-- Double-entry: every transaction has exactly one DEBIT and one CREDIT of equal amount. Append-only (see V2).
CREATE TABLE ledger_entries (
    id             uuid PRIMARY KEY,
    transaction_id uuid        NOT NULL REFERENCES ledger_transactions (id),
    account_id     uuid        NOT NULL REFERENCES accounts (id),
    direction      varchar(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor   bigint      NOT NULL CHECK (amount_minor > 0),
    balance_after  bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_entries_account_time ON ledger_entries (account_id, created_at DESC);
CREATE INDEX ix_entries_tx ON ledger_entries (transaction_id);

-- Idempotency: one row per (user, key). The primary key is what makes concurrent duplicates safe.
CREATE TABLE idempotency_keys (
    user_id         uuid        NOT NULL,
    idem_key        varchar(80) NOT NULL,
    scope           varchar(40) NOT NULL,
    request_hash    char(64)    NOT NULL,
    status          varchar(12) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_body   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX ix_idem_created ON idempotency_keys (created_at);

-- Transactional outbox: written in the same DB transaction as the ledger change.
CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    aggregate_type  varchar(40) NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      varchar(60) NOT NULL,
    payload         jsonb       NOT NULL,
    status          varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    locked_until    timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    processed_at    timestamptz
);
CREATE INDEX ix_outbox_dispatch ON outbox_events (status, next_attempt_at);

-- Inbox: makes each handler's effect happen at most once per event even if the event is redelivered.
CREATE TABLE processed_events (
    event_id     uuid        NOT NULL,
    handler      varchar(80) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, handler)
);

CREATE TABLE notifications (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL,
    event_id    uuid        NOT NULL,
    kind        varchar(30) NOT NULL,
    message     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, user_id, kind)
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE audit_logs (
    id           uuid PRIMARY KEY,
    actor_id     uuid,
    action       varchar(60) NOT NULL,
    entity_type  varchar(40),
    entity_id    varchar(64),
    details      jsonb,
    ip_address   varchar(64),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs (actor_id, created_at DESC);
