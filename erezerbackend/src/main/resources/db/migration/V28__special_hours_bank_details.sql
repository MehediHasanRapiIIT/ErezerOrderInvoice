-- Hours that differ from someone's normal shift, on certain dates or a weekday. No user means everyone.
CREATE TABLE attendance_special_hours
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT REFERENCES users (id) ON DELETE CASCADE,
    week_day       VARCHAR(10),
    date_from      DATE,
    date_to        DATE,
    day_off        BOOLEAN     NOT NULL DEFAULT FALSE,
    check_in_time  TIME,
    check_out_time TIME,
    note           VARCHAR(120),
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CHECK ((week_day IS NOT NULL AND date_from IS NULL AND date_to IS NULL)
        OR (week_day IS NULL AND date_from IS NOT NULL AND date_to IS NOT NULL AND date_to >= date_from)),
    CHECK (day_off OR (check_in_time IS NOT NULL AND check_out_time IS NOT NULL AND check_out_time > check_in_time))
);

CREATE INDEX ix_attendance_special_hours_user ON attendance_special_hours (user_id);

-- Bank accounts: where to send the salary.
ALTER TABLE employee_salary_accounts ADD COLUMN branch VARCHAR(120);
ALTER TABLE employee_salary_accounts ADD COLUMN routing_number VARCHAR(30);
