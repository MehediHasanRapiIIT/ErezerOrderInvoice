-- Advance: what the customer paid before delivery (e.g. by bKash). Due is what is still to be collected
-- (total − advance); it is what the courier collects on a cash-on-delivery order.
ALTER TABLE orders
    ADD COLUMN advance_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT orders_advance_chk CHECK (advance_amount >= 0 AND advance_amount <= total);

ALTER TABLE orders
    ADD COLUMN due_amount NUMERIC(12, 2) GENERATED ALWAYS AS (total - advance_amount) STORED;

CREATE INDEX orders_advance_idx ON orders (advance_amount) WHERE advance_amount > 0;
