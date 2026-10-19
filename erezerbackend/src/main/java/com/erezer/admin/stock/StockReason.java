package com.erezer.admin.stock;

/** Why a variant's stock changed. */
public enum StockReason {
    ADDED("Added"),
    ADJUSTED("Stock updated"),
    ORDER_PLACED("Order placed"),
    ORDER_CHANGED("Order edited"),
    ORDER_CANCELLED("Order cancelled"),
    ORDER_RETURNED("Order returned"),
    ORDER_DELETED("Order deleted"),
    EXCHANGE("Exchange");

    private final String label;

    StockReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
