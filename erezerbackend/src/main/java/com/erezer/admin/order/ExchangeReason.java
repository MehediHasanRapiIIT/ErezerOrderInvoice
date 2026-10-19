package com.erezer.admin.order;

/** Why a customer exchanges items; each reason suggests who should pay the exchange delivery. */
public enum ExchangeReason {
    WRONG_SIZE("Wrong size", DeliveryPaidBy.CUSTOMER),
    DEFECTIVE("Defective product", DeliveryPaidBy.EREZER),
    WRONG_ITEM_SENT("Wrong item sent", DeliveryPaidBy.EREZER),
    CHANGED_MIND("Customer changed mind", DeliveryPaidBy.CUSTOMER),
    OTHER("Other", DeliveryPaidBy.CUSTOMER);

    private final String label;
    private final DeliveryPaidBy suggestedPayer;

    ExchangeReason(String label, DeliveryPaidBy suggestedPayer) {
        this.label = label;
        this.suggestedPayer = suggestedPayer;
    }

    public String label() {
        return label;
    }

    public DeliveryPaidBy suggestedPayer() {
        return suggestedPayer;
    }
}
