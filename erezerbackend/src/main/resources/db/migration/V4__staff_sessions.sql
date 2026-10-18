-- Access tokens issued before this moment are rejected (set on deactivation, password reset and
-- role/permission changes), so those changes take effect on the user's next request.
ALTER TABLE users ADD COLUMN sessions_valid_after TIMESTAMPTZ;
