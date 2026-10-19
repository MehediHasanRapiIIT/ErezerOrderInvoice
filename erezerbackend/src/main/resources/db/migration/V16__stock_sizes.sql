-- Stock by size. The admin keeps one list of sizes; each variant carries some of them, and stock is counted per
-- variant + size (a "stock item"). stock_variants.quantity stays as the variant's total across its sizes.

CREATE TABLE sizes
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(20) NOT NULL,
    position   INTEGER     NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    -- The one "No size" row holding stock counted before sizes existed; not managed in the sizes list.
    legacy     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_sizes_name ON sizes (lower(name));

INSERT INTO sizes (name, position, legacy, created_at, updated_at)
VALUES ('No size', 0, TRUE, NOW(), NOW()),
       ('S', 1, FALSE, NOW(), NOW()),
       ('M', 2, FALSE, NOW(), NOW()),
       ('L', 3, FALSE, NOW(), NOW()),
       ('XL', 4, FALSE, NOW(), NOW()),
       ('XXL', 5, FALSE, NOW(), NOW());

CREATE TABLE stock_items
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id BIGINT      NOT NULL REFERENCES stock_variants (id) ON DELETE CASCADE,
    size_id    BIGINT      NOT NULL REFERENCES sizes (id),
    quantity   INTEGER     NOT NULL CHECK (quantity >= 0),
    -- Sizes taken off a variant are hidden, not deleted: orders and history still point at them.
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX ux_stock_items_variant_size ON stock_items (variant_id, size_id);
CREATE INDEX ix_stock_items_size ON stock_items (size_id);

-- Every existing variant's count moves into its "No size" item, so nothing is lost and old orders still balance.
INSERT INTO stock_items (variant_id, size_id, quantity, created_at, updated_at)
SELECT v.id, s.id, v.quantity, NOW(), NOW()
FROM stock_variants v
CROSS JOIN sizes s
WHERE s.legacy;

ALTER TABLE stock_movements
    ADD COLUMN stock_item_id BIGINT REFERENCES stock_items (id) ON DELETE CASCADE;
UPDATE stock_movements m
SET stock_item_id = i.id
FROM stock_items i
WHERE i.variant_id = m.variant_id;
ALTER TABLE stock_movements
    ALTER COLUMN stock_item_id SET NOT NULL;
CREATE INDEX ix_stock_movements_item ON stock_movements (stock_item_id, created_at DESC);

ALTER TABLE order_items
    ADD COLUMN stock_item_id BIGINT REFERENCES stock_items (id) ON DELETE SET NULL;
UPDATE order_items oi
SET stock_item_id = i.id
FROM stock_items i
WHERE i.variant_id = oi.variant_id;
