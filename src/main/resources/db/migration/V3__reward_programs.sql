-- Reward programs: named, amount-bearing bonuses. `transfers.reference_id`
-- points at `code` when `type = 'BONUS'` (see V2__ledger.sql).
CREATE TABLE reward_programs (
    code        TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    amount      BIGINT NOT NULL CHECK (amount > 0)
);

INSERT INTO reward_programs (code, description, amount)
VALUES ('signup-bonus-v1', 'First login bonus', 100);
