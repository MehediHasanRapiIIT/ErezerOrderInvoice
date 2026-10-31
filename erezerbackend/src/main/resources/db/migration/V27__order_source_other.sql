-- "Other" in the order form: a typed name for someone without a login, and a typed platform.
ALTER TABLE orders ADD COLUMN accepted_by_other VARCHAR(120);
ALTER TABLE orders ADD COLUMN platform_other VARCHAR(80);
