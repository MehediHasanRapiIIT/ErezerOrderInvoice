package com.erezer.admin.shop;

import com.erezer.admin.order.PaymentMethod;
import com.erezer.admin.order.dto.OrderDtos.CustomerRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

/** What the Erezer shop sends: one order per request, its photos as files alongside it. */
public final class ShopDtos {

    private ShopDtos() {
    }

    /**
     * An order from the shop. Its number (EZ-…) becomes the order's number here, so sending it twice finds the first
     * one instead of making another.
     */
    public record ShopOrder(
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "may contain only letters, digits and dashes")
            String orderNumber,
            /** When the customer ordered; left out, now. */
            Instant orderedAt,
            @NotNull PaymentMethod paymentMethod,
            /** Paid before delivery (e.g. bKash); the rest is collected on delivery. */
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal advance,
            @Valid @NotNull CustomerRequest customer,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal deliveryCharge,
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal discount,
            @NotEmpty(message = "Add at least one product") @Size(max = 50) List<@Valid ShopItem> items) {
    }

    /**
     * One product line. Its photos are the request's files named {@code item-<n>}, n counted from 0 in the order of
     * {@code items}; {@code imageDescription} is printed under each of them on the invoice.
     */
    public record ShopItem(
            @NotBlank @Size(max = 160) String productName,
            @Size(max = 60) String productCode,
            @Size(max = 1000) String description,
            @Size(max = 60) String color,
            @Size(max = 30) String size,
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @Min(1) @Max(10000) int quantity,
            @Size(max = 500) String imageDescription) {
    }

    public enum Outcome {
        /** Added to the order list. */
        CREATED,
        /** An order with this number is already here; nothing was changed. */
        EXISTS,
        /** Not added; {@code message} says why. */
        FAILED
    }

    /** What became of the order. {@code missingPhotos}: photos that couldn't be kept (not an image, too big). */
    public record ShopOrderResult(String orderNumber, Outcome outcome, Long orderId, String message, int missingPhotos) {
    }

    /** "Shop connection" for admins: whether a key is set, and which. */
    public record ConnectionView(boolean connected, String keyHint, Instant createdAt) {
    }

    /** A new key, shown once. */
    public record NewKey(String key) {
    }
}
