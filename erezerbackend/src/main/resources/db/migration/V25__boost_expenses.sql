-- Ad boosting: dollars spent each day, and dollars bought to pay for it.
-- Separate from the expenses table: nothing here changes the expense totals, net profit or the dashboard.
CREATE TABLE boost_costs
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cost_date  DATE           NOT NULL,
    usd        NUMERIC(12, 2) NOT NULL CHECK (usd > 0),
    rate       NUMERIC(12, 4) NOT NULL CHECK (rate > 0),
    bdt        NUMERIC(16, 2) NOT NULL,
    note       VARCHAR(200),
    created_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    version    BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_boost_costs_date ON boost_costs (cost_date DESC, id DESC);

CREATE TABLE boost_purchases
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_date DATE           NOT NULL,
    usd           NUMERIC(14, 2) NOT NULL CHECK (usd > 0),
    bdt           NUMERIC(16, 2) NOT NULL CHECK (bdt > 0),
    rate          NUMERIC(12, 4) NOT NULL,
    note          VARCHAR(200),
    created_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,
    version       BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_boost_purchases_date ON boost_purchases (purchase_date DESC, id DESC);

-- One purchase can have several parts, such as $5 for ৳500 and $6 for ৳600.
CREATE TABLE boost_purchase_lines
(
    purchase_id BIGINT         NOT NULL REFERENCES boost_purchases (id) ON DELETE CASCADE,
    position    INTEGER        NOT NULL,
    usd         NUMERIC(12, 2) NOT NULL CHECK (usd > 0),
    bdt         NUMERIC(14, 2) NOT NULL CHECK (bdt > 0),
    PRIMARY KEY (purchase_id, position)
);
