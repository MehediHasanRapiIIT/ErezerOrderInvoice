-- Returns as records (like exchanges): what came back, why, who paid the courier's return charge, the refund and
-- whether the pieces went back into stock.
--
--   REFUSED         the parcel came back without being delivered (the whole order)
--   AFTER_DELIVERY  the customer sent back some or all products after delivery
--
-- Money: the courier always charges Erezer the return fee. When the customer pays it, it is deducted from the
-- refund (after delivery) or covered by the customer (refused, e.g. out of an advance).

CREATE TABLE order_returns
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT         NOT NULL REFERENCES orders (id),
    kind          VARCHAR(20)    NOT NULL CHECK (kind IN ('REFUSED', 'AFTER_DELIVERY')),
    reason        VARCHAR(30)    NOT NULL,
    return_charge NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (return_charge >= 0),
    paid_by       VARCHAR(10)    NOT NULL CHECK (paid_by IN ('CUSTOMER', 'EREZER')),
    -- What the customer paid for the returned products (after their share of the order discount).
    items_value   NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (items_value >= 0),
    -- Money handed back: items value − the return charge when the customer pays it (after delivery);
    -- for a refused parcel, any advance the customer paid less the charge they cover.
    refund_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (refund_amount >= 0),
    restock       BOOLEAN        NOT NULL,
    note          VARCHAR(1000),
    created_by    BIGINT,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,
    -- Set when the return no longer stands (the order was moved out of Returned again).
    cancelled_at  TIMESTAMPTZ
);

CREATE INDEX ix_order_returns_order ON order_returns (order_id);
CREATE INDEX ix_order_returns_created ON order_returns (created_at) WHERE cancelled_at IS NULL;

CREATE TABLE order_return_items
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    return_id     BIGINT         NOT NULL REFERENCES order_returns (id) ON DELETE CASCADE,
    order_item_id BIGINT         NOT NULL REFERENCES order_items (id),
    quantity      INTEGER        NOT NULL CHECK (quantity > 0),
    unit_credit   NUMERIC(12, 2) NOT NULL CHECK (unit_credit >= 0),
    product_name  VARCHAR(160)   NOT NULL,
    product_code  VARCHAR(60),
    color         VARCHAR(80),
    size          VARCHAR(30)
);

CREATE INDEX ix_order_return_items_return ON order_return_items (return_id);
CREATE INDEX ix_order_return_items_item ON order_return_items (order_item_id);

-- Orders already marked Returned become return records, so the dashboard keeps counting them the same way.
INSERT INTO order_returns (order_id, kind, reason, return_charge, paid_by, items_value, refund_amount, restock,
                           created_by, created_at, updated_at)
SELECT o.id,
       CASE WHEN o.delivered_at IS NULL THEN 'REFUSED' ELSE 'AFTER_DELIVERY' END,
       'OTHER',
       o.return_delivery_charge,
       'EREZER',
       CASE WHEN o.delivered_at IS NULL THEN 0 ELSE o.product_amount END,
       CASE WHEN o.delivered_at IS NULL THEN 0 ELSE o.product_amount END,
       TRUE,
       NULL,
       COALESCE(o.returned_at, o.updated_at),
       COALESCE(o.returned_at, o.updated_at)
FROM orders o
WHERE o.status = 'RETURNED' AND o.deleted_at IS NULL;
