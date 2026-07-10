-- Seed symbol-to-asset-class classifier map for common equity and fund tickers.
-- Idempotent: truncates and reinserts on every migration run.
TRUNCATE TABLE security_asset_class;

INSERT INTO security_asset_class (symbol, asset_class) VALUES
  -- US equities and stock funds
  ('VOO','us_stock'), ('VTI','us_stock'), ('VUG','us_stock'), ('FXAIX','us_stock'),
  ('SCHD','us_stock'), ('AAPL','us_stock'), ('AMZN','us_stock'), ('GOOG','us_stock'),
  ('MSFT','us_stock'), ('NVDA','us_stock'),
  -- International equities and international stock funds
  ('VXUS','intl_stock'), ('VEA','intl_stock'), ('VWO','intl_stock'), ('EFA','intl_stock'),
  -- Bonds and bond funds
  ('BND','bond'), ('AGG','bond'), ('BNDX','bond'), ('VCIT','bond'), ('TLT','bond'),
  -- Cash and money market
  ('SPAXX','cash'), ('VMFXX','cash'), ('SGOV','cash'), ('BIL','cash');
