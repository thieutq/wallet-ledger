-- owner_id NULL + type = 'SYSTEM' marks house/treasury accounts, which act as
-- the counterparty for direct credits/debits into a player's wallet.
CREATE TABLE accounts (
    id         TEXT PRIMARY KEY,
    owner_id   TEXT REFERENCES users(id),
    type       TEXT NOT NULL DEFAULT 'PLAYER',
    -- single-currency virtual wallet for now; relax accounts_currency_valid
    -- below (accounts_owner_currency_unique already supports it) when a
    -- second currency needs to be introduced.
    currency   TEXT NOT NULL DEFAULT 'COINS',
    balance    BIGINT NOT NULL DEFAULT 0,        -- settled funds, minor units
    held       BIGINT NOT NULL DEFAULT 0,        -- reserved by active holds
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- available = balance - held; a hold can never reserve more than is settled.
    -- SYSTEM accounts (treasury/house) are exempt from BOTH constraints below:
    -- they are the counterparty that issues credits (bonuses, mission rewards,
    -- ...) into player wallets, so they must be able to go negative without
    -- limit — held_within_balance needs the same exemption as
    -- balance_non_negative, or the first-ever credit (balance 0 -> negative)
    -- would violate held(0) <= balance(negative) and be rejected.
    CONSTRAINT balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0),
    CONSTRAINT held_within_balance  CHECK (type = 'SYSTEM' OR (held >= 0 AND held <= balance)),
    CONSTRAINT accounts_type_valid CHECK (type IN ('PLAYER', 'SYSTEM')),
    CONSTRAINT accounts_currency_valid CHECK (currency = 'COINS'),
    -- one wallet per player per currency; NULL owner_id (system accounts) is exempt.
    CONSTRAINT accounts_owner_currency_unique UNIQUE (owner_id, currency)
);

CREATE INDEX accounts_owner_idx ON accounts(owner_id) WHERE owner_id IS NOT NULL;

-- type + reference_id capture *why* a balance moved (mission reward, purchase,
-- admin action, ...) so every transfer is a self-explaining, permanent record.
CREATE TABLE transfers (
    id              TEXT PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,         -- makes retries safe
    from_account_id TEXT NOT NULL REFERENCES accounts(id),
    to_account_id   TEXT NOT NULL REFERENCES accounts(id),
    amount          BIGINT NOT NULL,
    currency        TEXT NOT NULL DEFAULT 'COINS',
    status          TEXT NOT NULL,
    type            TEXT NOT NULL,                -- BONUS | PURCHASE | REFUND | ADMIN_ADJUSTMENT | HOLD_CAPTURE | TRANSFER
    reference_id    TEXT,                         -- external entity id: mission/order/admin ticket id, or reward_programs.code when type = 'BONUS'
    created_by      TEXT REFERENCES users(id),    -- admin/service actor; NULL for fully automated system transfers
    metadata        JSONB,                        -- freeform context, e.g. admin adjustment note
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT amount_positive CHECK (amount > 0),
    CONSTRAINT distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT transfers_status_valid CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT transfers_type_valid CHECK (type IN ('BONUS', 'PURCHASE', 'REFUND', 'ADMIN_ADJUSTMENT', 'HOLD_CAPTURE', 'TRANSFER')),
    -- single-currency system for now; see accounts_currency_valid.
    CONSTRAINT transfers_currency_valid CHECK (currency = 'COINS')
);

CREATE INDEX transfers_reference_idx ON transfers(reference_id) WHERE reference_id IS NOT NULL;

-- One row per side of a transfer. Amount is signed: negative = debit, positive
-- = credit. The two rows of a transfer always sum to zero (double-entry).
CREATE TABLE entries (
    id          TEXT PRIMARY KEY,
    transfer_id TEXT NOT NULL REFERENCES transfers(id),
    account_id  TEXT NOT NULL REFERENCES accounts(id),
    amount      BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX entries_account_idx  ON entries(account_id);
CREATE INDEX entries_transfer_idx ON entries(transfer_id);

-- An authorization hold reserves funds (raising accounts.held) without moving
-- them, until it is captured (settled into a transfer) or released.
CREATE TABLE holds (
    id                  TEXT PRIMARY KEY,
    idempotency_key     TEXT NOT NULL UNIQUE,
    from_account_id     TEXT NOT NULL REFERENCES accounts(id),
    to_account_id       TEXT NOT NULL REFERENCES accounts(id),
    amount              BIGINT NOT NULL,                 -- reserved
    captured            BIGINT NOT NULL DEFAULT 0,       -- settled (<= amount)
    status              TEXT NOT NULL,                   -- active | captured | voided | expired
    type                TEXT NOT NULL,                   -- PURCHASE | ADMIN_ADJUSTMENT
    reference_id        TEXT,                            -- external entity id (order id, admin ticket id...)
    created_by          TEXT REFERENCES users(id),       -- admin/service actor; NULL for fully automated holds
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,                     -- NULL = no expiry
    capture_transfer_id TEXT REFERENCES transfers(id),   -- set once captured
    CONSTRAINT hold_amount_positive CHECK (amount > 0),
    CONSTRAINT hold_captured_valid  CHECK (captured >= 0 AND captured <= amount),
    CONSTRAINT holds_status_valid  CHECK (status IN ('active', 'captured', 'voided', 'expired')),
    CONSTRAINT holds_type_valid    CHECK (type IN ('PURCHASE', 'ADMIN_ADJUSTMENT'))
);

CREATE INDEX holds_active_expiry_idx ON holds(expires_at) WHERE status = 'active';
CREATE INDEX holds_reference_idx     ON holds(reference_id) WHERE reference_id IS NOT NULL;

-- Transactional outbox: written in the SAME transaction as the business
-- change it describes (audit/notification side channel only — never used
-- for the credit/debit/hold/capture/void mutation itself, see
-- docs/04-design-decisions.md #9).
CREATE TABLE outbox_events (
    id           TEXT PRIMARY KEY,
    event_type   TEXT NOT NULL,          -- e.g. 'TRANSFER_COMPLETED', 'HOLD_CAPTURED'
    payload      JSONB NOT NULL,
    status       TEXT NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_valid CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
);

CREATE INDEX outbox_events_pending_idx ON outbox_events(created_at) WHERE status = 'PENDING';

-- System/service credentials for the system/admin-facing ledger endpoints
-- (X-Api-Key header -> ROLE_SERVICE). Hashed the same way user passwords are.
CREATE TABLE api_clients (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    hashed_api_key  TEXT NOT NULL UNIQUE,
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
