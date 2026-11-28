-- The Erezer shop (erezer.com) sends its website orders here. It proves who it is with a
-- secret key an admin creates in "Shop connection"; only the key's SHA-256 is kept, plus
-- its last characters so the admin can tell which key is in use. One key at a time.
CREATE TABLE shop_connection
(
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    key_hash   VARCHAR(64) NOT NULL,
    key_hint   VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT REFERENCES users (id) ON DELETE SET NULL
);
