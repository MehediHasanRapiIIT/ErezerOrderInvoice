-- Stock per variant. A variant is whatever the admin names it ("Black", "Navy Blue - M", ...). Order items pick a
-- variant; the stock follows the order automatically (taken when placed, given back when cancelled, returned or
-- deleted), and every change is kept in stock_movements.

CREATE TABLE stock_variants
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(80) NOT NULL,
    quantity   INTEGER     NOT NULL CHECK (quantity >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_by BIGINT,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

-- Names are unique regardless of case among variants that still exist.
CREATE UNIQUE INDEX ux_stock_variants_name ON stock_variants (lower(name)) WHERE deleted_at IS NULL;

-- One row per change of a variant's quantity. order_id has no foreign key: the history outlives deleted orders.
CREATE TABLE stock_movements
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id     BIGINT      NOT NULL REFERENCES stock_variants (id) ON DELETE CASCADE,
    change         INTEGER     NOT NULL CHECK (change <> 0),
    quantity_after INTEGER     NOT NULL,
    reason         VARCHAR(30) NOT NULL,
    order_id       BIGINT,
    note           VARCHAR(200),
    created_by     BIGINT,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_stock_movements_variant ON stock_movements (variant_id, created_at DESC);
CREATE INDEX ix_stock_movements_order ON stock_movements (order_id) WHERE order_id IS NOT NULL;

ALTER TABLE order_items
    ADD COLUMN variant_id BIGINT REFERENCES stock_variants (id) ON DELETE SET NULL;

-- Orders placed before stock existed never took stock, so they must never give any back.
ALTER TABLE orders
    ADD COLUMN stock_tracked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders
    ALTER COLUMN stock_tracked SET DEFAULT TRUE;
