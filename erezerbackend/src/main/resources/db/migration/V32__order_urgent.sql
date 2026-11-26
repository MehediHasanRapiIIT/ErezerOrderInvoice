-- Orders marked for urgent delivery: shown in the list, filterable, and printed on the invoice. Not sent to the courier.
ALTER TABLE orders ADD COLUMN urgent BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_orders_urgent ON orders (urgent) WHERE urgent;
