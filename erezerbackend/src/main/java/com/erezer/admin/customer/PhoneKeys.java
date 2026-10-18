package com.erezer.admin.customer;

/**
 * Normalises phone numbers exactly like the {@code orders.customer_phone_key} column (migration V5):
 * digits only, and a leading Bangladesh country code {@code 880} becomes the local {@code 0}.
 */
public final class PhoneKeys {

    /** A complete Bangladeshi mobile number has 11 digits (01XXXXXXXXX). */
    public static final int MIN_LOOKUP_DIGITS = 11;

    private PhoneKeys() {
    }

    public static String normalize(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.startsWith("880") ? digits.substring(2) : digits;
    }
}
