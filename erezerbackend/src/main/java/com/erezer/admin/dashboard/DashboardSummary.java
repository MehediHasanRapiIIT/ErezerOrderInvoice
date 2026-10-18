package com.erezer.admin.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard figures for a date range, plus the same figures for the preceding period of equal length.
 *
 * @param granularity {@code DAY} or {@code MONTH}, the bucket size of {@code series}
 */
public record DashboardSummary(
        LocalDate from,
        LocalDate to,
        Totals current,
        Totals previous,
        OrderCounts orders,
        String granularity,
        List<SeriesPoint> series,
        List<CategoryTotal> topExpenseCategories) {

    /**
     * Cash-basis totals for the period (see {@code DashboardService#totals} for the business rules).
     *
     * @param totalRevenue         Σ order total of deliveries in the period (what customers paid, incl. delivery charge)
     * @param productRevenue       Σ (total − delivery charge) of those deliveries: product sales after discounts
     * @param deliveryCharges      Σ delivery charge collected on those deliveries (passed on to the courier)
     * @param deliveryChargeDifference Σ (delivery charge collected − courier's real charge) where the courier reported
     *                             its charge; zero otherwise
     * @param returnRevenue        Σ order total of returns recorded in the period
     * @param returnProductRevenue Σ (total − delivery charge) of those returns
     * @param returnDeliveryTotal  courier cost of those returns: delivery + return charge when refused at the door,
     *                             only the return charge when returned after delivery; the courier's reported charge
     *                             when it is known
     * @param refunds              product value refunded for returns of orders that had been delivered
     * @param expenses             Σ expenses dated in the period
     * @param netProfit            productRevenue + deliveryChargeDifference − refunds − returnDeliveryTotal − expenses
     * @param deliveredOrders      deliveries in the period (including orders returned later)
     * @param returnedOrders       returns recorded in the period
     * @param refusedOrders        of those returns, how many were refused at the door (never delivered)
     */
    public record Totals(
            BigDecimal totalRevenue,
            BigDecimal productRevenue,
            BigDecimal deliveryCharges,
            BigDecimal deliveryChargeDifference,
            BigDecimal returnRevenue,
            BigDecimal returnProductRevenue,
            BigDecimal returnDeliveryTotal,
            BigDecimal refunds,
            BigDecimal expenses,
            BigDecimal netProfit,
            long deliveredOrders,
            long returnedOrders,
            long refusedOrders,
            long expenseCount) {
    }

    /** @param returnRate returns ÷ (deliveries + refusals) in the range, 0–1; null when nothing reached an outcome */
    public record OrderCounts(long placed, long cancelled, long delivered, long returned, Double returnRate) {
    }

    /** One chart bucket: product revenue and expenses for a day or a month. */
    public record SeriesPoint(LocalDate bucket, BigDecimal productRevenue, BigDecimal expenses) {
    }

    public record CategoryTotal(String name, BigDecimal total) {
    }
}
