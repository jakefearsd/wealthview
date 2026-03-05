-- Add 'opening_balance' to the allowed transaction types for position snapshot imports

ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('buy', 'sell', 'dividend', 'deposit', 'withdrawal', 'opening_balance'));
