-- Daily expenses: employees and moderators apply for what they spent, and an admin approves or rejects it.
-- Separate from the expenses table: nothing here changes the expense totals, net profit or the dashboard.
CREATE TABLE daily_expense_categories
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(60) NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_daily_expense_categories_name ON daily_expense_categories (lower(name));

CREATE TABLE daily_expenses
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT         NOT NULL REFERENCES users (id),
    expense_date     DATE           NOT NULL,
    -- Empty means "Other", and other_category then says what it was.
    category_id      BIGINT REFERENCES daily_expense_categories (id),
    other_category   VARCHAR(60),
    amount           NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    details          VARCHAR(500),
    receipt_id       BIGINT REFERENCES stored_files (id),
    status           VARCHAR(10)    NOT NULL DEFAULT 'PENDING',
    paid             NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (paid >= 0),
    rejection_reason VARCHAR(500),
    reviewed_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    CHECK (category_id IS NOT NULL OR other_category IS NOT NULL),
    CHECK (paid <= amount)
);

CREATE INDEX ix_daily_expenses_date ON daily_expenses (expense_date DESC, id DESC);
CREATE INDEX ix_daily_expenses_user ON daily_expenses (user_id);
CREATE INDEX ix_daily_expenses_category ON daily_expenses (category_id);
CREATE INDEX ix_daily_expenses_receipt ON daily_expenses (receipt_id);
CREATE INDEX ix_daily_expenses_pending ON daily_expenses (expense_date) WHERE status = 'PENDING';

-- This menu is for employees and moderators.
INSERT INTO user_permissions (user_id, permission)
SELECT id, 'DAILY_EXPENSE_CREATE' FROM users WHERE role <> 'ADMIN'
ON CONFLICT DO NOTHING;
