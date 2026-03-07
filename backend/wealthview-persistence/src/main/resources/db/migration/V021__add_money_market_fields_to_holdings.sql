-- Add money market indicator and interest rate to holdings
ALTER TABLE holdings ADD COLUMN is_money_market boolean NOT NULL DEFAULT false;
ALTER TABLE holdings ADD COLUMN money_market_rate numeric(7, 4);
