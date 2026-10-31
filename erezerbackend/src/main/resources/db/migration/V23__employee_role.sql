-- Employee: like a moderator, holds only the permissions an admin grants.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'MODERATOR', 'EMPLOYEE'));
