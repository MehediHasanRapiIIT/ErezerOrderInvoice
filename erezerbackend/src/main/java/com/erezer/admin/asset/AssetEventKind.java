package com.erezer.admin.asset;

public enum AssetEventKind {
    CREATED,
    UPDATED,
    MOVED,
    STATUS,
    /** Repair or service, usually with a cost. The cost stays with the asset; it is not an expense. */
    REPAIR
}
