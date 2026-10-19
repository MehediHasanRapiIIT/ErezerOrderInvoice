-- "Forgot password" from the sign-in page: the request waits for an admin, who sets a new password on the Staff page.
-- One open request per account; asking again only moves its time forward.
CREATE TABLE password_reset_requests
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL,
    requested_ip VARCHAR(64),
    resolved_at  TIMESTAMPTZ,
    resolved_by  BIGINT REFERENCES users (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX ux_password_reset_requests_open ON password_reset_requests (user_id) WHERE resolved_at IS NULL;
