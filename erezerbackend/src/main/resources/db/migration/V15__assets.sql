-- Shop assets: things the business owns (CCTV, furniture, computers...). Kept apart from expenses on purpose:
-- nothing here reaches the expense totals, net profit or the dashboard.
CREATE TABLE asset_categories
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(60) NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_asset_categories_name ON asset_categories (LOWER(name));

INSERT INTO asset_categories (name, created_at, updated_at)
SELECT name, NOW(), NOW()
FROM (VALUES ('Furniture'), ('Electronics'), ('CCTV & Security'), ('Machines'), ('Tools'), ('Other')) AS seed(name);

CREATE TABLE assets
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(120)   NOT NULL,
    category_id     BIGINT         NOT NULL REFERENCES asset_categories (id),
    quantity        INT            NOT NULL CHECK (quantity > 0),
    unit_cost       NUMERIC(12, 2) NOT NULL CHECK (unit_cost >= 0),
    total_cost      NUMERIC(14, 2) NOT NULL CHECK (total_cost >= 0),
    purchase_date   DATE           NOT NULL,
    supplier        VARCHAR(120),
    payment_method  VARCHAR(20),
    reference       VARCHAR(80),
    location        VARCHAR(80),
    serial_number   VARCHAR(120),
    warranty_until  DATE,
    status          VARCHAR(20)    NOT NULL,
    -- Set when the asset is sold or disposed of; sale_price only when sold.
    disposed_on     DATE,
    sale_price      NUMERIC(12, 2) CHECK (sale_price >= 0),
    notes           VARCHAR(1000),
    photo_file_id   BIGINT REFERENCES stored_files (id),
    receipt_file_id BIGINT REFERENCES stored_files (id),
    deleted_at      TIMESTAMPTZ,
    created_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_assets_purchase ON assets (purchase_date DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_assets_category ON assets (category_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_assets_photo ON assets (photo_file_id);
CREATE INDEX ix_assets_receipt ON assets (receipt_file_id);

-- The asset's history: created, edited, moved, status changes, and repair / service records (with a cost).
CREATE TABLE asset_events
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id    BIGINT         NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    kind        VARCHAR(20)    NOT NULL,
    event_date  DATE           NOT NULL,
    description VARCHAR(500)   NOT NULL,
    cost        NUMERIC(12, 2) CHECK (cost >= 0),
    created_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ    NOT NULL
);

CREATE INDEX ix_asset_events_asset ON asset_events (asset_id, event_date DESC, id DESC);
