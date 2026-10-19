package com.erezer.admin.order;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Order lifecycle. The main flow runs PLACED → … → DELIVERED and may skip steps forward.
 * CANCELLED is possible until the parcel leaves (before SHIPPED); RETURNED only after it has.
 * Staff with the "change status freely" permission may move an order to any status (see {@link #selectable()}).
 */
public enum OrderStatus {

    PLACED,
    ACCEPTED,
    /** No longer used (orders in it became PROCESSING); kept so old timeline entries can still be read. */
    IN_PRODUCTION,
    PROCESSING,
    SHIPPED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    RETURNED;

    /** The happy path, in order. */
    public static final List<OrderStatus> FLOW =
            List.of(PLACED, ACCEPTED, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED);

    /** Statuses shown under the "History" tab; everything else is "Active". */
    public static final Set<OrderStatus> FINISHED = EnumSet.of(DELIVERED, CANCELLED, RETURNED);

    public static final Set<OrderStatus> PENDING = EnumSet.of(PLACED, ACCEPTED, IN_PRODUCTION, PROCESSING);

    /** Every status an order can be put in (all but the retired IN_PRODUCTION). */
    public static Set<OrderStatus> selectable() {
        EnumSet<OrderStatus> all = EnumSet.allOf(OrderStatus.class);
        all.remove(IN_PRODUCTION);
        return all;
    }

    public static final Set<OrderStatus> IN_TRANSIT = EnumSet.of(SHIPPED, OUT_FOR_DELIVERY);

    public boolean isFinished() {
        return FINISHED.contains(this);
    }

    /** Has the parcel been handed to the courier? */
    public boolean isDispatched() {
        int index = FLOW.indexOf(this);
        return this == RETURNED || index >= FLOW.indexOf(SHIPPED);
    }

    public Set<OrderStatus> allowedNext() {
        EnumSet<OrderStatus> next = EnumSet.noneOf(OrderStatus.class);
        if (this == CANCELLED || this == RETURNED) {
            return next;
        }
        int index = FLOW.indexOf(this);
        next.addAll(FLOW.subList(index + 1, FLOW.size()));
        if (isDispatched()) {
            next.add(RETURNED);
        } else {
            next.add(CANCELLED);
        }
        return next;
    }

    public boolean canMoveTo(OrderStatus target) {
        return target != IN_PRODUCTION && allowedNext().contains(target);
    }
}
