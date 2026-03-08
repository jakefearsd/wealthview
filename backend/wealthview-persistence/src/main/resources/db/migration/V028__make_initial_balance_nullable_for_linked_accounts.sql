-- Linked projection accounts derive their balance from the real account at runtime.
-- NULL initial_balance means "resolve from linked account at runtime."
-- Non-null initial_balance means "use this user-entered value" (hypothetical accounts).

ALTER TABLE projection_accounts ALTER COLUMN initial_balance DROP NOT NULL;
ALTER TABLE projection_accounts ALTER COLUMN initial_balance DROP DEFAULT;

UPDATE projection_accounts SET initial_balance = NULL WHERE linked_account_id IS NOT NULL;
