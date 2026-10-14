-- Staff accounts (admins and moderators) and their granular permissions.
CREATE TABLE users
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name             VARCHAR(120) NOT NULL,
    email                 VARCHAR(160) NOT NULL,
    phone                 VARCHAR(30),
    password_hash         VARCHAR(100) NOT NULL,
    role                  VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'MODERATOR')),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_users_email ON users (LOWER(email));

CREATE TABLE user_permissions
(
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    permission VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, permission)
);

-- Opaque refresh tokens; only a SHA-256 hash is stored. Rotated on every use.
CREATE TABLE refresh_tokens
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    -- ROTATED (replaced by a newer token), LOGOUT, or REVOKED (password change, deactivation, theft)
    revoke_reason VARCHAR(20) CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL,
    user_agent VARCHAR(255),
    ip_address VARCHAR(45)
);

CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens (expires_at);
