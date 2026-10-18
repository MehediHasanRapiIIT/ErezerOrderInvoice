-- Courier integration (Steadfast). Credentials are stored encrypted (AES-GCM, key from COURIER_ENCRYPTION_KEY);
-- the webhook token is stored only as a SHA-256 hash.
CREATE TABLE courier_settings
(
    id                    SMALLINT PRIMARY KEY CHECK (id = 1),
    enabled               BOOLEAN      NOT NULL DEFAULT FALSE,
    base_url              VARCHAR(200) NOT NULL DEFAULT 'https://portal.packzy.com/api/v1',
    api_key_enc           TEXT,
    secret_key_enc        TEXT,
    webhook_token_hash    VARCHAR(64),
    default_delivery_type SMALLINT     NOT NULL DEFAULT 0 CHECK (default_delivery_type IN (0, 1)),
    updated_by            BIGINT REFERENCES users (id) ON DELETE SET NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
INSERT INTO courier_settings (id) VALUES (1);

-- What the courier actually charged us for an order (from Steadfast); null when unknown.
ALTER TABLE orders ADD COLUMN courier_charge NUMERIC(12, 2) CHECK (courier_charge >= 0);

-- One booking per order. CONFIRMING = request sent but outcome unknown (timeout); resolved by the poller.
CREATE TABLE courier_shipments
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id          BIGINT         NOT NULL REFERENCES orders (id),
    provider          VARCHAR(20)    NOT NULL,
    state             VARCHAR(20)    NOT NULL CHECK (state IN ('CONFIRMING', 'BOOKED')),
    invoice           VARCHAR(100)   NOT NULL,
    consignment_id    VARCHAR(40),
    tracking_code     VARCHAR(60),
    tracking_link     VARCHAR(300),
    cod_amount        NUMERIC(12, 2) NOT NULL,
    delivery_type     SMALLINT       NOT NULL,
    note              VARCHAR(500),
    item_description  VARCHAR(255),
    courier_status    VARCHAR(60),
    courier_status_at TIMESTAMPTZ,
    tracking_message  VARCHAR(500),
    delivery_charge   NUMERIC(12, 2),
    needs_attention   BOOLEAN        NOT NULL DEFAULT FALSE,
    request_json      TEXT,
    response_json     TEXT,
    created_by        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_courier_shipments_order ON courier_shipments (order_id);
CREATE INDEX ix_courier_shipments_consignment ON courier_shipments (consignment_id);
CREATE INDEX ix_courier_shipments_invoice ON courier_shipments (invoice);
CREATE INDEX ix_courier_shipments_sync ON courier_shipments (state, courier_status_at);

-- Every webhook / poll / manual refresh, for audit and de-duplication.
CREATE TABLE courier_events
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id     BIGINT REFERENCES courier_shipments (id) ON DELETE CASCADE,
    source          VARCHAR(20)  NOT NULL CHECK (source IN ('WEBHOOK', 'POLL', 'MANUAL', 'BOOKING')),
    dedupe_key      VARCHAR(128),
    courier_status  VARCHAR(60),
    payload         TEXT,
    received_at     TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_courier_events_dedupe ON courier_events (dedupe_key);
CREATE INDEX ix_courier_events_shipment ON courier_events (shipment_id, received_at);

-- Bulk sends run in the background; results survive closing the browser.
CREATE TABLE courier_batches
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider    VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED')),
    total       INTEGER     NOT NULL,
    succeeded   INTEGER     NOT NULL DEFAULT 0,
    failed      INTEGER     NOT NULL DEFAULT 0,
    created_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE TABLE courier_batch_items
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id         BIGINT         NOT NULL REFERENCES courier_batches (id) ON DELETE CASCADE,
    order_id         BIGINT         NOT NULL REFERENCES orders (id),
    status           VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    cod_amount       NUMERIC(12, 2) NOT NULL,
    delivery_type    SMALLINT       NOT NULL,
    note             VARCHAR(500),
    item_description VARCHAR(255),
    message          VARCHAR(500),
    tracking_code    VARCHAR(60),
    position         INTEGER        NOT NULL
);
CREATE INDEX ix_courier_batch_items_batch ON courier_batch_items (batch_id, position);
