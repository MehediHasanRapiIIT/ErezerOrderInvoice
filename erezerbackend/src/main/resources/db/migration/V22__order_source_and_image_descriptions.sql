-- A description for each product image, printed under the image on the invoice.
ALTER TABLE order_item_images ADD COLUMN description VARCHAR(500);

-- Who accepted the order and where it came from. Older orders stay empty rather than guessing.
ALTER TABLE orders ADD COLUMN accepted_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE orders ADD COLUMN platform VARCHAR(20);

CREATE INDEX ix_orders_accepted_by ON orders (accepted_by);
