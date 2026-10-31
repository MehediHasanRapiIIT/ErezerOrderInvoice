-- Attendance: moderators check in and out from their phones at one of the admin's locations.

-- Places where attendance can be given. The admin sets each one's position (and adds or removes places).
CREATE TABLE work_locations
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(80) NOT NULL,
    latitude   DOUBLE PRECISION,
    longitude  DOUBLE PRECISION,
    radius_m   INTEGER     NOT NULL DEFAULT 100 CHECK (radius_m > 0),
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX ux_work_locations_name ON work_locations (lower(name));

INSERT INTO work_locations (name, created_at, updated_at)
VALUES ('Shop', NOW(), NOW()),
       ('Warehouse', NOW(), NOW());

-- Each employee's own working hours. Only employees with a shift take part in attendance.
CREATE TABLE attendance_shifts
(
    user_id        BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    check_in_time  TIME        NOT NULL,
    check_out_time TIME        NOT NULL,
    grace_minutes  INTEGER     NOT NULL DEFAULT 0 CHECK (grace_minutes >= 0),
    -- Days off in the week, e.g. FRIDAY or FRIDAY,SATURDAY. Empty means none.
    weekly_off     VARCHAR(70) NOT NULL DEFAULT '',
    -- Days before this aren't counted as absent.
    starts_on      DATE        NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CHECK (check_out_time > check_in_time)
);

-- One row per employee and day. Shift times are copied, so changing a shift never rewrites past days.
CREATE TABLE attendance_records
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id               BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    work_date             DATE           NOT NULL,
    shift_start           TIME           NOT NULL,
    shift_end             TIME           NOT NULL,

    check_in_at           TIMESTAMPTZ,
    check_in_latitude     DOUBLE PRECISION,
    check_in_longitude    DOUBLE PRECISION,
    check_in_location_id  BIGINT REFERENCES work_locations (id),
    check_in_distance_m   INTEGER,
    check_in_in_range     BOOLEAN        NOT NULL DEFAULT FALSE,
    check_in_photo_id     BIGINT REFERENCES stored_files (id),
    check_in_reason       VARCHAR(500),
    late_minutes          INTEGER        NOT NULL DEFAULT 0,

    check_out_at          TIMESTAMPTZ,
    check_out_latitude    DOUBLE PRECISION,
    check_out_longitude   DOUBLE PRECISION,
    check_out_location_id BIGINT REFERENCES work_locations (id),
    check_out_distance_m  INTEGER,
    check_out_in_range    BOOLEAN        NOT NULL DEFAULT FALSE,
    check_out_reason      VARCHAR(500),
    early_minutes         INTEGER        NOT NULL DEFAULT 0,

    -- NONE: nothing to review. PENDING: late, early or away from every location, waiting for the admin.
    review_status         VARCHAR(10)    NOT NULL DEFAULT 'NONE',
    late_fee              NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (late_fee >= 0),
    reviewed_by           BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at           TIMESTAMPTZ,
    review_note           VARCHAR(500),
    edited_by             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    edited_at             TIMESTAMPTZ,
    edit_note             VARCHAR(500),

    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,
    version               BIGINT         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_attendance_user_day ON attendance_records (user_id, work_date);
CREATE INDEX ix_attendance_day ON attendance_records (work_date);
CREATE INDEX ix_attendance_pending ON attendance_records (work_date) WHERE review_status = 'PENDING';
CREATE INDEX ix_attendance_photo ON attendance_records (check_in_photo_id);

-- Moderators are the employees: they can all mark their own attendance.
INSERT INTO user_permissions (user_id, permission)
SELECT id, 'ATTENDANCE_MARK' FROM users WHERE role = 'MODERATOR'
ON CONFLICT DO NOTHING;
