-- Remote attendance: people working from home press Reply On and Reply Off. No camera, no location.

-- Who works remotely. Everyone else gives attendance at the office.
CREATE TABLE remote_workers
(
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);

-- One row per person and day: the hours that applied, and what the admin decided.
CREATE TABLE remote_days
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    work_date      DATE           NOT NULL,
    -- Copied when the day starts; empty when no hours were set, so nothing can be late.
    shift_start    TIME,
    shift_end      TIME,
    grace_minutes  INTEGER        NOT NULL DEFAULT 0,
    late_minutes   INTEGER        NOT NULL DEFAULT 0,
    early_minutes  INTEGER        NOT NULL DEFAULT 0,
    late_reason    VARCHAR(500),
    early_reason   VARCHAR(500),
    done_at        TIMESTAMPTZ,
    not_turned_off BOOLEAN        NOT NULL DEFAULT FALSE,
    review_status  VARCHAR(10)    NOT NULL DEFAULT 'NONE',
    late_fee       NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (late_fee >= 0),
    reviewed_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at    TIMESTAMPTZ,
    review_note    VARCHAR(500),
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL
);

CREATE UNIQUE INDEX ux_remote_days_user_day ON remote_days (user_id, work_date);
CREATE INDEX ix_remote_days_date ON remote_days (work_date);

-- Each Reply On to Reply Off.
CREATE TABLE remote_sessions
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    day_id      BIGINT       NOT NULL REFERENCES remote_days (id) ON DELETE CASCADE,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    on_at       TIMESTAMPTZ  NOT NULL,
    off_at      TIMESTAMPTZ,
    -- Closed by the system because Reply Off was never pressed.
    auto_closed BOOLEAN      NOT NULL DEFAULT FALSE,
    image_id    BIGINT REFERENCES stored_files (id),
    note        VARCHAR(300),
    off_note    VARCHAR(300),
    edited_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    edit_note   VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CHECK (off_at IS NULL OR off_at > on_at)
);

-- At most one open session each: a double press can never open two.
CREATE UNIQUE INDEX ux_remote_sessions_open ON remote_sessions (user_id) WHERE off_at IS NULL;
CREATE INDEX ix_remote_sessions_day ON remote_sessions (day_id, on_at);
CREATE INDEX ix_remote_sessions_image ON remote_sessions (image_id);

-- Moderators can use Reply On and Off once an admin makes them remote.
INSERT INTO user_permissions (user_id, permission)
SELECT id, 'REMOTE_MARK' FROM users WHERE role = 'MODERATOR'
ON CONFLICT DO NOTHING;
