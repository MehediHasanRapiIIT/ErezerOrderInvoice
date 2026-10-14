-- Uploaded binaries (product images now, expense receipts later). Rows not referenced by any
-- feature table are purged by a scheduled job after a grace period.
CREATE TABLE stored_files
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_key   VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    original_name VARCHAR(255),
    uploaded_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX ux_stored_files_key ON stored_files (object_key);
CREATE INDEX ix_stored_files_created ON stored_files (created_at);

CREATE TABLE orders
(
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number           VARCHAR(30)    NOT NULL,
    status                 VARCHAR(30)    NOT NULL,
    payment_method         VARCHAR(20)    NOT NULL,
    customer_name          VARCHAR(120)   NOT NULL,
    customer_phone         VARCHAR(30)    NOT NULL,
    customer_email         VARCHAR(160),
    customer_address       VARCHAR(500)   NOT NULL,
    subtotal               NUMERIC(12, 2) NOT NULL CHECK (subtotal >= 0),
    discount               NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    delivery_charge        NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (delivery_charge >= 0),
    total                  NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    return_delivery_charge NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (return_delivery_charge >= 0),
    courier                VARCHAR(80),
    tracking_number        VARCHAR(80),
    delivered_at           TIMESTAMPTZ,
    returned_at            TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    deleted_at             TIMESTAMPTZ,
    created_by             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ    NOT NULL,
    updated_at             TIMESTAMPTZ    NOT NULL,
    version                BIGINT         NOT NULL DEFAULT 0
);

-- Unique across deleted rows too, so a soft-deleted order number is never reused.
CREATE UNIQUE INDEX ux_orders_number ON orders (order_number);
CREATE INDEX ix_orders_status_created ON orders (status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_orders_created ON orders (created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_orders_phone ON orders (customer_phone);
CREATE INDEX ix_orders_delivered ON orders (delivered_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_orders_returned ON orders (returned_at) WHERE deleted_at IS NULL;

CREATE TABLE order_items
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    position     INTEGER        NOT NULL,
    product_name VARCHAR(160)   NOT NULL,
    product_code VARCHAR(60)    NOT NULL,
    description  VARCHAR(1000),
    color        VARCHAR(60),
    size         VARCHAR(30),
    unit_price   NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    quantity     INTEGER        NOT NULL CHECK (quantity > 0),
    line_total   NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0)
);

CREATE INDEX ix_order_items_order ON order_items (order_id);
CREATE INDEX ix_order_items_code ON order_items (LOWER(product_code));

CREATE TABLE order_item_images
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_item_id BIGINT  NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    file_id       BIGINT  NOT NULL REFERENCES stored_files (id),
    position      INTEGER NOT NULL
);

CREATE INDEX ix_order_item_images_item ON order_item_images (order_item_id);
CREATE INDEX ix_order_item_images_file ON order_item_images (file_id);

-- Audit trail of status transitions (the order "timeline").
CREATE TABLE order_status_history
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status   VARCHAR(30) NOT NULL,
    note        VARCHAR(1000),
    changed_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    changed_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_order_status_history_order ON order_status_history (order_id, changed_at);

-- Staff-only notes, never shown to customers.
CREATE TABLE order_notes
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    body       VARCHAR(2000) NOT NULL,
    created_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_order_notes_order ON order_notes (order_id, created_at);
