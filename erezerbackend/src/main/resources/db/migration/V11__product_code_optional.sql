-- The product code is optional on orders (and therefore on the exchange copies of order items).
ALTER TABLE order_items ALTER COLUMN product_code DROP NOT NULL;
ALTER TABLE order_exchange_items ALTER COLUMN product_code DROP NOT NULL;
