package com.erezer.admin.order;

public enum PaymentMethod {

    CASH("Cash on delivery"),
    BKASH("bKash"),
    NAGAD("Nagad"),
    BANK_TRANSFER("Bank transfer"),
    CARD("Card");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    /** Human-readable name for documents (invoice, Excel). */
    public String label() {
        return label;
    }
}
