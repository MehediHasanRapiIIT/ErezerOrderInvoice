package com.erezer.admin.security;

import com.erezer.admin.common.error.ApiException;

/**
 * Minimum password rules for staff accounts.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 72; // bcrypt ignores bytes beyond 72
    public static final String DESCRIPTION =
            "Password must be 10-72 characters and contain at least one letter and one digit";

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }
        return password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit);
    }

    public static void validate(String password) {
        if (!isValid(password)) {
            throw ApiException.badRequest("WEAK_PASSWORD", DESCRIPTION);
        }
    }
}
