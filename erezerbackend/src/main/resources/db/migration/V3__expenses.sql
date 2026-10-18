-- Expense categories are managed in the panel; they are deactivated rather than deleted once used.
CREATE TABLE expense_categories
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(60) NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_expense_categories_name ON expense_categories (LOWER(name));

INSERT INTO expense_categories (name, created_at, updated_at)
SELECT name, NOW(), NOW()
FROM (VALUES ('Raw materials'), ('Printing'), ('Packaging'), ('Courier & delivery'), ('Salaries'),
             ('Rent'), ('Utilities'), ('Marketing'), ('Equipment'), ('Other')) AS seed(name);

CREATE TABLE expenses
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expense_date     DATE           NOT NULL,
    category_id      BIGINT         NOT NULL REFERENCES expense_categories (id),
    description      VARCHAR(200)   NOT NULL,
    amount           NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    payment_method   VARCHAR(20)    NOT NULL,
    reference        VARCHAR(80),
    notes            VARCHAR(1000),
    receipt_file_id  BIGINT REFERENCES stored_files (id),
    deleted_at       TIMESTAMPTZ,
    created_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_expenses_date ON expenses (expense_date DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_expenses_category ON expenses (category_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_expenses_receipt ON expenses (receipt_file_id);
