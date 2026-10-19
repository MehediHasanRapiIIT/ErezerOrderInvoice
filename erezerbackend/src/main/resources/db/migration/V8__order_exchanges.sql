-- Product exchanges. An exchange is its own order (order_type = 'EXCHANGE') linked to the delivered order it
-- replaces items of, so the original order, its invoice and its dashboard figures never change.
--
-- Money on an exchange order:
--   subtotal         value of the replacement items
--   exchange_credit  value of the returned items (original prices, less their share of the original discount)
--   delivery_charge  the exchange delivery charge; exchange_delivery_paid_by says who pays it
--   total            what the rider collects: the positive price difference + the delivery if the customer pays

ALTER TABLE orders
    ADD COLUMN order_type                VARCHAR(20)   NOT NULL DEFAULT 'SALE',
    ADD COLUMN exchange_of_order_id      BIGINT        REFERENCES orders (id),
    ADD COLUMN exchange_credit           NUMERIC(12, 2),
    ADD COLUMN exchange_refund           BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN exchange_delivery_paid_by VARCHAR(10),
    ADD COLUMN exchange_reason           VARCHAR(30),
    -- "Regular Fit T-Shirt (M) ×1, …": what the rider collects; printed in the courier note.
    ADD COLUMN exchange_returned_summary VARCHAR(500),
    ADD CONSTRAINT orders_order_type_chk CHECK (order_type IN ('SALE', 'EXCHANGE')),
    ADD CONSTRAINT orders_exchange_chk CHECK (
        order_type = 'SALE'
        OR (exchange_of_order_id IS NOT NULL
            AND exchange_credit IS NOT NULL AND exchange_credit >= 0
            AND exchange_delivery_paid_by IN ('CUSTOMER', 'EREZER')
            AND exchange_reason IS NOT NULL));

-- The same figures for every order, so the dashboard needs no special cases:
--   product_amount          product revenue when delivered (an exchange earns only a positive price difference)
--   exchange_refund_amount  money handed back when the replacement is cheaper and a refund was chosen
--   delivery_collected      delivery charge actually collected from the customer
ALTER TABLE orders
    ADD COLUMN product_amount NUMERIC(12, 2) GENERATED ALWAYS AS (
        CASE WHEN order_type = 'EXCHANGE' THEN GREATEST(subtotal - exchange_credit, 0)
             ELSE total - delivery_charge END) STORED,
    ADD COLUMN exchange_refund_amount NUMERIC(12, 2) GENERATED ALWAYS AS (
        CASE WHEN order_type = 'EXCHANGE' AND exchange_refund AND exchange_credit > subtotal
                 THEN exchange_credit - subtotal
             ELSE 0 END) STORED,
    ADD COLUMN delivery_collected NUMERIC(12, 2) GENERATED ALWAYS AS (
        CASE WHEN order_type = 'EXCHANGE' AND exchange_delivery_paid_by = 'EREZER' THEN 0
             ELSE delivery_charge END) STORED;

CREATE INDEX orders_exchange_of_idx ON orders (exchange_of_order_id) WHERE exchange_of_order_id IS NOT NULL;
CREATE INDEX orders_order_type_idx ON orders (order_type) WHERE order_type <> 'SALE';

-- Which original items (and how many of each) an exchange takes back. The product details are copied so the
-- exchange invoice and history stay readable whatever happens to the original order later.
CREATE TABLE order_exchange_items (
    id                   BIGSERIAL PRIMARY KEY,
    exchange_order_id    BIGINT         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    original_item_id     BIGINT         NOT NULL REFERENCES order_items (id),
    replacement_position INT            NOT NULL,
    quantity             INT            NOT NULL CHECK (quantity > 0),
    unit_credit          NUMERIC(12, 2) NOT NULL CHECK (unit_credit >= 0),
    product_name         VARCHAR(160)   NOT NULL,
    product_code         VARCHAR(60)    NOT NULL,
    color                VARCHAR(60),
    size                 VARCHAR(30)
);

CREATE INDEX order_exchange_items_exchange_idx ON order_exchange_items (exchange_order_id);
CREATE INDEX order_exchange_items_original_idx ON order_exchange_items (original_item_id);
