-- Employee records. Separate from users: a user is a login, an employee is a person the shop employs.
CREATE TABLE employees
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_code     VARCHAR(40)  NOT NULL,
    full_name         VARCHAR(120) NOT NULL,
    phone             VARCHAR(30)  NOT NULL,
    father_name       VARCHAR(120),
    father_phone      VARCHAR(30),
    mother_name       VARCHAR(120),
    mother_phone      VARCHAR(30),
    present_address   VARCHAR(500) NOT NULL,
    permanent_address VARCHAR(500),
    photo_image_id    BIGINT       NOT NULL REFERENCES stored_files (id),
    nid_number        VARCHAR(20)  NOT NULL,
    nid_image_id      BIGINT       NOT NULL REFERENCES stored_files (id),
    id_card_image_id  BIGINT REFERENCES stored_files (id),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_employees_code ON employees (lower(employee_code));
CREATE UNIQUE INDEX ux_employees_nid ON employees (nid_number);
CREATE INDEX ix_employees_name ON employees (lower(full_name));
CREATE INDEX ix_employees_photo_image ON employees (photo_image_id);
CREATE INDEX ix_employees_nid_image ON employees (nid_image_id);
CREATE INDEX ix_employees_id_card_image ON employees (id_card_image_id);

-- Where salary goes. Someone may have more than one.
CREATE TABLE employee_salary_accounts
(
    employee_id    BIGINT       NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    position       INTEGER      NOT NULL,
    account_type   VARCHAR(10)  NOT NULL,
    provider       VARCHAR(80),
    account_name   VARCHAR(120),
    account_number VARCHAR(60)  NOT NULL,
    PRIMARY KEY (employee_id, position)
);
