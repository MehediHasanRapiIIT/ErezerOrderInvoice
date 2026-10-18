-- Customers are derived from orders, identified by phone number. The key strips formatting and the
-- Bangladesh country code, so "+880 1646-065553", "8801646065553" and "01646065553" are one customer.
ALTER TABLE orders ADD COLUMN customer_phone_key VARCHAR(30) GENERATED ALWAYS AS (
    CASE
        WHEN regexp_replace(customer_phone, '[^0-9]', '', 'g') LIKE '880%'
            THEN substr(regexp_replace(customer_phone, '[^0-9]', '', 'g'), 3)
        ELSE regexp_replace(customer_phone, '[^0-9]', '', 'g')
    END
) STORED;

CREATE INDEX ix_orders_customer_phone_key ON orders (customer_phone_key, created_at DESC) WHERE deleted_at IS NULL;
