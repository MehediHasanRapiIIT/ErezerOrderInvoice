package com.erezer.admin.order.dto;

import com.erezer.admin.order.OrderStatus;
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
            Long version) {
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
            @NotBlank @Size(max = 60) String productCode,
            @Size(max = 1000) String description,
            @Size(max = 60) String color,
            @Size(max = 30) String size,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @Min(1) @Max(10000) int quantity,
            @Size(max = 10) List<@NotNull Long> imageIds) {
    }

    public record StatusChangeRequest(
            @NotNull OrderStatus status,
            @Size(max = 80) String courier,
            @Size(max = 80) String trackingNumber,
            @Size(max = 1000) String note,
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal returnDeliveryCharge) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String body) {
    }

    // ---- Responses ------------------------------------------------------------

    public record OrderSummary(
            long id,
            String orderNumber,
            String customerName,
            String customerPhone,
            BigDecimal total,
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
            List<Note> notes) {
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
            List<Image> images) {
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
