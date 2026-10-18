package com.erezer.admin.courier;

import com.erezer.admin.customer.PhoneKeys;
import com.erezer.admin.order.Order;
import com.erezer.admin.order.OrderItem;
import com.erezer.admin.order.OrderStatus;
import com.erezer.admin.order.PaymentMethod;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the parcel that would be booked for an order and lists every problem up front, so nothing fails as a
 * surprise at Steadfast. Limits follow Steadfast's create_order validation.
 */
@Component
class ParcelPlanner {

    /** Orders that are confirmed but not yet with a courier. */
    static final Set<OrderStatus> SENDABLE = EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.IN_PRODUCTION, OrderStatus.PROCESSING);

    static final int MAX_NAME = 100;
    static final int MAX_ADDRESS = 490;
    static final int MAX_NOTE = 480;
    static final int MAX_ITEM_DESCRIPTION = 255;
    static final BigDecimal MAX_COD = new BigDecimal("1000000");
    /** Steadfast replaces these with spaces; doing it here means the preview shows exactly what is booked. */
    private static final Pattern STRIPPED = Pattern.compile("[{};<>$]");
    private static final Pattern BD_MOBILE = Pattern.compile("^01[3-9]\\d{8}$");

    /** Values an operator may change in the preview; null means "use the default". */
    record Overrides(BigDecimal codAmount, Integer deliveryType, String note, String itemDescription) {

        static final Overrides NONE = new Overrides(null, null, null, null);
    }

    record Plan(SteadfastClient.Parcel parcel, BigDecimal codAmount, BigDecimal defaultCod, List<String> problems,
            String blockedReason) {

        boolean ready() {
            return blockedReason == null && problems.isEmpty();
        }
    }

    Plan plan(Order order, Overrides overrides, int defaultDeliveryType, boolean alreadySent) {
        List<String> problems = new ArrayList<>();
        String blocked = null;
        if (alreadySent) {
            blocked = "Already sent to the courier";
        } else if (!SENDABLE.contains(order.getStatus())) {
            blocked = switch (order.getStatus()) {
                case PLACED -> "Accept the order before sending it to the courier";
                case SHIPPED, OUT_FOR_DELIVERY -> "Already shipped";
                default -> "A " + order.getStatus().name().toLowerCase().replace('_', ' ') + " order cannot be sent";
            };
        }

        String phone = PhoneKeys.normalize(order.getCustomerPhone());
        if (!BD_MOBILE.matcher(phone).matches()) {
            problems.add("Phone must be a Bangladeshi mobile number (01XXXXXXXXX)");
        }
        String name = clean(order.getCustomerName());
        if (name.length() > MAX_NAME) {
            problems.add("Customer name is longer than " + MAX_NAME + " characters");
        }
        String address = clean(order.getCustomerAddress());
        if (address.length() > MAX_ADDRESS) {
            problems.add("Address is longer than " + MAX_ADDRESS + " characters");
        }

        BigDecimal defaultCod = defaultCod(order);
        BigDecimal cod = overrides.codAmount() != null ? overrides.codAmount() : defaultCod;
        if (cod.signum() < 0 || cod.compareTo(MAX_COD) > 0) {
            problems.add("COD amount must be between ৳0 and ৳1,000,000");
        } else if (cod.stripTrailingZeros().scale() > 2) {
            problems.add("COD amount can have at most 2 decimal places");
        }

        int deliveryType = overrides.deliveryType() != null ? overrides.deliveryType() : defaultDeliveryType;
        if (deliveryType != 0 && deliveryType != 1) {
            problems.add("Delivery type must be home delivery or hub pickup");
        }
        String note = blankToNull(clean(overrides.note()));
        if (note != null && note.length() > MAX_NOTE) {
            problems.add("Note is longer than " + MAX_NOTE + " characters");
        }
        String items = blankToNull(clean(overrides.itemDescription() != null ? overrides.itemDescription() : describe(order.getItems())));
        if (items != null && items.length() > MAX_ITEM_DESCRIPTION) {
            problems.add("Item description is longer than " + MAX_ITEM_DESCRIPTION + " characters");
        }

        BigDecimal codSent = problems.stream().anyMatch(p -> p.startsWith("COD")) ? BigDecimal.ZERO : cod.stripTrailingZeros();
        if (codSent.scale() < 0) {
            codSent = codSent.setScale(0);
        }
        SteadfastClient.Parcel parcel = new SteadfastClient.Parcel(order.getOrderNumber(), name, phone, address, codSent,
                note, items, deliveryType);
        return new Plan(parcel, cod, defaultCod, List.copyOf(problems), blocked);
    }

    /** Cash on delivery collects the full order total; prepaid orders (bKash, Nagad, bank, card) collect nothing. */
    static BigDecimal defaultCod(Order order) {
        return order.getPaymentMethod() == PaymentMethod.CASH ? order.getTotal() : BigDecimal.ZERO;
    }

    /** "Regular Fit T-Shirt ×1, Cargo Pant ×2", shortened to fit Steadfast's limit. */
    static String describe(List<OrderItem> items) {
        String text = items.stream()
                .map(i -> clean(i.getProductName()) + (i.getSize() == null ? "" : " (" + i.getSize() + ")") + " ×" + i.getQuantity())
                .collect(Collectors.joining(", "));
        return text.length() <= MAX_ITEM_DESCRIPTION ? text : text.substring(0, MAX_ITEM_DESCRIPTION - 1) + "…";
    }

    static String clean(String value) {
        return value == null ? null : STRIPPED.matcher(value).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
