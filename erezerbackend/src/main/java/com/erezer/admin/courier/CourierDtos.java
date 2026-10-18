package com.erezer.admin.courier;

import com.erezer.admin.order.OrderStatus;
import com.erezer.admin.order.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CourierDtos {

    private CourierDtos() {
    }

    // ---- Requests -------------------------------------------------------------

    public record PreviewRequest(@NotEmpty @Size(max = 500) List<@NotNull Long> orderIds) {
    }

    /** Values edited in the preview. Null fields fall back to the defaults. */
    public record SendRequest(
            @DecimalMin("0") @DecimalMax("1000000") BigDecimal codAmount,
            @Min(0) @Max(1) Integer deliveryType,
            @Size(max = 480) String note,
            @Size(max = 255) String itemDescription) {
    }

    public record BatchItemRequest(
            @NotNull Long orderId,
            @DecimalMin("0") @DecimalMax("1000000") BigDecimal codAmount,
            @Min(0) @Max(1) Integer deliveryType,
            @Size(max = 480) String note,
            @Size(max = 255) String itemDescription) {
    }

    public record BatchRequest(@NotEmpty @Size(max = 500) List<@Valid @NotNull BatchItemRequest> items) {
    }

    // ---- Responses ------------------------------------------------------------

    public record CustomerHistory(long orders, long delivered, long returned) {
    }

    /** One order as it would be booked, with everything that blocks it. */
    public record ParcelPreview(
            long orderId,
            String orderNumber,
            OrderStatus orderStatus,
            String recipientName,
            String recipientPhone,
            String recipientAddress,
            PaymentMethod paymentMethod,
            BigDecimal orderTotal,
            BigDecimal codAmount,
            BigDecimal defaultCodAmount,
            int deliveryType,
            String note,
            String itemDescription,
            boolean ready,
            String blockedReason,
            List<String> problems,
            CustomerHistory customerHistory,
            String existingTrackingCode) {
    }

    public record PreviewResponse(List<ParcelPreview> parcels, int ready, BigDecimal totalCod) {
    }

    public record EventView(String source, String courierStatus, Instant receivedAt) {
    }

    public record ShipmentView(
            String provider,
            String state,
            String invoice,
            String consignmentId,
            String trackingCode,
            String trackingLink,
            BigDecimal codAmount,
            int deliveryType,
            String note,
            String itemDescription,
            String courierStatus,
            Instant courierStatusAt,
            String trackingMessage,
            BigDecimal deliveryCharge,
            boolean needsAttention,
            Instant createdAt,
            List<EventView> events) {
    }

    /** BOOKED, or CONFIRMING when Steadfast didn't answer and the booking will be confirmed automatically. */
    public record SendResult(String state, String message, ShipmentView shipment) {
    }

    public record BatchItemView(long orderId, String orderNumber, String customerName, String status, String message,
            String trackingCode, BigDecimal codAmount) {
    }

    public record BatchView(long id, String status, int total, int succeeded, int failed, int processed,
            Instant createdAt, Instant finishedAt, List<BatchItemView> items) {
    }
}
