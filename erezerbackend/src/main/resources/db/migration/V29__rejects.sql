-- Rejected pieces. One from stock has reduced that stock; "Other" is only a typed name and moves nothing.
CREATE TABLE rejects
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reject_date   DATE         NOT NULL,
    from_stock    BOOLEAN      NOT NULL,
    variant_id    BIGINT,
    stock_item_id BIGINT,
    variant_name  VARCHAR(120) NOT NULL,
    size          VARCHAR(30),
    kind          VARCHAR(10)  NOT NULL CHECK (kind IN ('SOLID', 'PRINTED')),
    quantity      INTEGER      NOT NULL CHECK (quantity > 0),
    note          VARCHAR(300),
    created_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CHECK (NOT from_stock OR stock_item_id IS NOT NULL)
);

CREATE INDEX ix_rejects_date ON rejects (reject_date DESC, id DESC);
