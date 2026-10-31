package com.erezer.admin.security;

public enum Role {
    /** Full access, including staff management. Always holds every {@link Permission}. */
    ADMIN,
    /** Holds only the permissions an admin explicitly granted. */
    MODERATOR,
    /** Same as a moderator: only what was granted. Usually just attendance. */
    EMPLOYEE;

    public String authority() {
        return "ROLE_" + name();
    }
}
