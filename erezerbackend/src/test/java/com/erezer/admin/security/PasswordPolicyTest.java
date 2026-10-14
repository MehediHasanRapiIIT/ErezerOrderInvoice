package com.erezer.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "Secret12345, true",
            "short1, false",
            "onlylettersnodigits, false",
            "1234567890, false",
    })
    void validatesLengthLetterAndDigit(String password, boolean expected) {
        assertThat(PasswordPolicy.isValid(password)).isEqualTo(expected);
    }
}
