package com.erezer.admin.order;

/** Why products came back; each reason suggests who pays the return charge and whether the pieces can be resold. */
public enum ReturnReason {
    REFUSED("Refused at the door", DeliveryPaidBy.EREZER, true),
    DID_NOT_LIKE("Didn't like it", DeliveryPaidBy.CUSTOMER, true),
    WRONG_SIZE("Wrong size", DeliveryPaidBy.CUSTOMER, true),
    DEFECTIVE("Defective product", DeliveryPaidBy.EREZER, false),
    WRONG_ITEM_SENT("Wrong item sent", DeliveryPaidBy.EREZER, true),
    DAMAGED_IN_DELIVERY("Damaged in delivery", DeliveryPaidBy.EREZER, false),
    OTHER("Other", DeliveryPaidBy.CUSTOMER, true);

    private final String label;
    private final DeliveryPaidBy suggestedPayer;
    private final boolean resellable;

    ReturnReason(String label, DeliveryPaidBy suggestedPayer, boolean resellable) {
        this.label = label;
        this.suggestedPayer = suggestedPayer;
        this.resellable = resellable;
    }

    public String label() {
        return label;
    }

    public DeliveryPaidBy suggestedPayer() {
        return suggestedPayer;
    }

    /** Whether returned pieces normally go back into stock. */
    public boolean resellable() {
        return resellable;
    }
}
