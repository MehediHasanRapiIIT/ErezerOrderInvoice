package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void placedCanMoveForwardOrBeCancelledButNotReturned() {
        assertThat(OrderStatus.PLACED.allowedNext())
                .contains(OrderStatus.ACCEPTED, OrderStatus.DELIVERED, OrderStatus.CANCELLED)
                .doesNotContain(OrderStatus.RETURNED, OrderStatus.PLACED);
    }

    @Test
    void dispatchedOrdersCanBeReturnedButNotCancelled() {
        assertThat(OrderStatus.SHIPPED.allowedNext())
                .containsExactlyInAnyOrder(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, OrderStatus.RETURNED);
        assertThat(OrderStatus.DELIVERED.allowedNext()).containsExactly(OrderStatus.RETURNED);
    }

    @Test
    void terminalStatusesAllowNothing() {
        assertThat(OrderStatus.CANCELLED.allowedNext()).isEmpty();
        assertThat(OrderStatus.RETURNED.allowedNext()).isEmpty();
    }

    @Test
    void cannotMoveBackwards() {
        assertThat(OrderStatus.PROCESSING.canMoveTo(OrderStatus.ACCEPTED)).isFalse();
    }
}
