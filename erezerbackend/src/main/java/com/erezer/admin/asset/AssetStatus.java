package com.erezer.admin.asset;

/** Where an asset stands. Sold and disposed assets leave the active value but stay on record. */
public enum AssetStatus {
    IN_USE("In use"),
    UNDER_REPAIR("Under repair"),
    IN_STORAGE("In storage"),
    DAMAGED("Damaged"),
    SOLD("Sold"),
    DISPOSED("Disposed");

    private final String label;

    AssetStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Still owned by the shop (counts in the asset value). */
    public boolean owned() {
        return this != SOLD && this != DISPOSED;
    }
}
