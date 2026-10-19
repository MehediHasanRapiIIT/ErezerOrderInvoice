package com.erezer.admin.order.dto;

import com.erezer.admin.order.DeliveryPaidBy;
import com.erezer.admin.order.ExchangeReason;
import com.erezer.admin.order.OrderStatus;
import com.erezer.admin.order.OrderType;
import com.erezer.admin.order.ReturnKind;
import com.erezer.admin.order.ReturnReason;
import com.erezer.admin.order.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    // ---- Requests -------------------------------------------------------------

    /**
     * Create or update an order. Totals are always computed by the server.
     *
     * @param orderNumber optional; generated when blank
     * @param version     required on update for optimistic locking; ignored on create
     */
    public record OrderRequest(
            @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "may contain only letters, digits and dashes")
            String orderNumber,
            @NotNull PaymentMethod paymentMethod,
            @Valid @NotNull CustomerRequest customer,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal deliveryCharge,
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal discount,
            @NotEmpty(message = "Add at least one product") @Size(max = 50) List<@Valid OrderItemRequest> items,
            Long version,
            /** Optional: paid before delivery; the due (total − advance) is collected on delivery. */
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal advance) {
    }

    public record CustomerRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[0-9+\\-\\s]{6,30}$", message = "is not a valid phone number")
            String phone,
            @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 500) String address) {
    }

    public record OrderItemRequest(
            @NotBlank @Size(max = 160) String productName,
            /** Optional. */
            @Size(max = 60) String productCode,
            @Size(max = 1000) String description,
            @Size(max = 60) String color,
            @Size(max = 30) String size,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @Min(1) @Max(10000) int quantity,
            @Size(max = 10) List<@NotNull Long> imageIds,
            /** The stock variant (its name replaces {@code color}); null = not tracked in stock. */
            Long variantId,
            /** The variant's size (its name replaces {@code size}); may be left out when the variant has one size. */
            Long stockItemId) {
    }

    public record StatusChangeRequest(
            @NotNull OrderStatus status,
            @Size(max = 80) String courier,
            @Size(max = 80) String trackingNumber,
            @Size(max = 1000) String note,
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal returnDeliveryCharge,
            /** When returning: who pays the return charge (Erezer when not given). */
            DeliveryPaidBy returnPaidBy,
            /** When returning: why (refused at the door / other when not given). */
            ReturnReason returnReason) {
    }

    /** Products sent back after delivery. */
    public record ReturnRequest(
            @NotEmpty(message = "Choose at least one product") @Size(max = 50) List<@Valid ReturnLineRequest> items,
            @NotNull ReturnReason reason,
            @NotNull DeliveryPaidBy paidBy,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal returnCharge,
            @Size(max = 1000) String note) {
    }

    public record ReturnLineRequest(@NotNull Long orderItemId, @Min(1) @Max(10000) int quantity) {
    }

    /** Correct a return afterwards: reason, return charge, who paid it. */
    public record ReturnUpdateRequest(
            @NotNull ReturnReason reason,
            @NotNull DeliveryPaidBy paidBy,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal returnCharge,
            @Size(max = 1000) String note) {
    }

    public record ReturnView(long id, ReturnKind kind, ReturnReason reason, String reasonLabel, BigDecimal returnCharge,
            DeliveryPaidBy paidBy, BigDecimal itemsValue, BigDecimal refundAmount, boolean restock, String note,
            UserRef createdBy, Instant createdAt, boolean active, List<ReturnedItem> items) {
    }

    /** Everything the return page needs for one delivered order. */
    public record ReturnOptions(long orderId, String orderNumber, Customer customer, Instant deliveredAt,
            BigDecimal deliveryCharge, List<ReturnableItem> items, List<ReturnView> returns) {
    }

    public record ReturnableItem(long id, String productName, String productCode, String color, String size,
            BigDecimal unitPrice, BigDecimal unitCredit, int quantity, int alreadyReturned, int alreadyExchanged,
            int remaining, List<Image> images) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String body) {
    }

    /**
     * @param courierCharge         what the courier really charged; null = unknown (the delivery charge is used)
     * @param returnDeliveryCharge  returned orders only: the courier's return charge
     */
    public record CourierCostRequest(
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal courierCharge,
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal returnDeliveryCharge) {
    }

    /**
     * Internal: what the courier is paid for the order. Never printed on the invoice.
     *
     * @param applicable    false until the parcel is shipped
     * @param courierCharge the courier's real charge when known (from Steadfast or entered by staff)
     * @param estimated     true when the real charge is unknown and the delivery charge stands in for it
     * @param refused       returned without ever being delivered (the charge then covers both ways)
     */
    public record CourierCost(boolean applicable, BigDecimal courierCharge, BigDecimal returnDeliveryCharge,
            BigDecimal total, boolean estimated, boolean refused) {
    }

    /**
     * Exchange items of a delivered order.
     *
     * @param refund            only matters when the replacement is cheaper: hand the difference back or not
     * @param overrideTimeLimit admins only: allow an exchange after the time limit
     */
    public record ExchangeRequest(
            @NotEmpty(message = "Choose at least one item to exchange") @Size(max = 50) List<@Valid ExchangeLineRequest> items,
            @NotNull ExchangeReason reason,
            @NotNull DeliveryPaidBy deliveryPaidBy,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal deliveryCharge,
            Boolean refund,
            Boolean overrideTimeLimit,
            @Size(max = 1000) String note,
            /** Optional, like a normal order's; generated when empty. */
            @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "may contain only letters, digits and dashes")
            String orderNumber) {

        /** Missing means no. */
        public boolean refundChosen() {
            return Boolean.TRUE.equals(refund);
        }

        public boolean timeLimitOverridden() {
            return Boolean.TRUE.equals(overrideTimeLimit);
        }
    }

    /** Take back {@code quantity} of an original item and send {@code replacement} instead (same quantity). */
    public record ExchangeLineRequest(
            @NotNull Long originalItemId,
            @Min(1) @Max(10000) int quantity,
            @Valid @NotNull OrderItemRequest replacement) {
    }

    // ---- Responses ------------------------------------------------------------

    public record OrderSummary(
            long id,
            String orderNumber,
            OrderType orderType,
            String customerName,
            String customerPhone,
            BigDecimal total,
            BigDecimal advance,
            BigDecimal due,
            PaymentMethod paymentMethod,
            OrderStatus status,
            Instant createdAt) {
    }

    public record OrderDetail(
            long id,
            String orderNumber,
            OrderStatus status,
            PaymentMethod paymentMethod,
            Customer customer,
            List<Item> items,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal deliveryCharge,
            BigDecimal total,
            BigDecimal advance,
            BigDecimal due,
            BigDecimal returnDeliveryCharge,
            String courier,
            String trackingNumber,
            UserRef createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version,
            boolean editable,
            List<OrderStatus> allowedNextStatuses,
            List<StatusChange> history,
            List<Note> notes,
            OrderType orderType,
            ExchangeInfo exchange,
            List<ExchangeRef> exchanges,
            CourierCost courierCost,
            /** The viewer may move this order to any status (the list above then holds every other status). */
            boolean freeStatusChange,
            /** Returns of this order, newest first (cancelled ones included, marked inactive). */
            List<ReturnView> returns,
            /** Delivered, with some products sent back. */
            boolean partlyReturned) {
    }

    /** On an exchange order: what it replaces and how the money works out. */
    public record ExchangeInfo(
            long ofOrderId,
            String ofOrderNumber,
            ExchangeReason reason,
            String reasonLabel,
            DeliveryPaidBy deliveryPaidBy,
            BigDecimal credit,
            BigDecimal priceDifference,
            boolean refund,
            BigDecimal refundAmount,
            List<ReturnedItem> returnedItems) {
    }

    public record ReturnedItem(String productName, String productCode, String color, String size, int quantity,
            BigDecimal unitCredit, BigDecimal credit) {
    }

    /** An exchange made for an order. */
    public record ExchangeRef(long id, String orderNumber, OrderStatus status, Instant createdAt) {
    }

    /** A delivered order found by number or phone, offered on the exchange page. */
    public record ExchangeCandidate(long id, String orderNumber, String customerName, String customerPhone,
            BigDecimal total, Instant deliveredAt) {
    }

    /** Everything the exchange page needs for one order. */
    public record ExchangeOptions(
            long orderId,
            String orderNumber,
            Customer customer,
            Instant deliveredAt,
            Instant deadline,
            boolean withinTimeLimit,
            boolean canOverrideTimeLimit,
            int timeLimitDays,
            BigDecimal defaultDeliveryCharge,
            List<ExchangeableItem> items,
            List<ExchangeRef> exchanges) {
    }

    public record ExchangeableItem(
            long id,
            String productName,
            String productCode,
            String description,
            String color,
            String size,
            BigDecimal unitPrice,
            BigDecimal unitCredit,
            int quantity,
            int alreadyExchanged,
            int remaining,
            List<Image> images,
            Long variantId,
            Long stockItemId) {
    }

    public record Customer(String name, String phone, String email, String address) {
    }

    public record Item(
            long id,
            String productName,
            String productCode,
            String description,
            String color,
            String size,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            List<Image> images,
            Long variantId,
            Long stockItemId) {
    }

    public record Image(long fileId, String url) {
    }

    public record StatusChange(OrderStatus fromStatus, OrderStatus toStatus, String note, UserRef changedBy,
            Instant changedAt) {
    }

    public record Note(long id, String body, UserRef author, Instant createdAt) {
    }

    public record UserRef(long id, String name) {
    }

    public record OrderStats(long total, long pending, long inTransit, long delivered) {
    }
}
