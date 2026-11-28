-- "Order to Print": orders not yet shipped are printed from a queue. The tick says the products are printed;
-- the order still leaves the queue only when it is shipped (or cancelled).
ALTER TABLE orders ADD COLUMN printed_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN printed_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
