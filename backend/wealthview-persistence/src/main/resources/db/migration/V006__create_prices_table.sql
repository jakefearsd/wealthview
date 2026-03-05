-- Prices table with composite primary key (symbol, date)

CREATE TABLE prices (
    symbol text NOT NULL,
    date date NOT NULL,
    close_price numeric(19, 4) NOT NULL,
    source text NOT NULL CHECK (source IN ('manual', 'finnhub')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, date)
);
