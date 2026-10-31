package com.erezer.admin.boost;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BoostDtos {

    private BoostDtos() {
    }

    /** @param version only on edit: the version the form loaded */
    public record CostRequest(
            @NotNull LocalDate date,
            @NotNull @DecimalMin("0.01") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal usd,
            @NotNull @DecimalMin("0.01") @DecimalMax("99999") @Digits(integer = 8, fraction = 4) BigDecimal rate,
            @Size(max = 200) String note,
            Long version) {
    }

    public record PurchaseLine(
            @NotNull @DecimalMin("0.01") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal usd,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999") @Digits(integer = 12, fraction = 2) BigDecimal bdt) {
    }

    /** @param version only on edit: the version the form loaded */
    public record PurchaseRequest(
            @NotNull LocalDate date,
            @NotEmpty @Size(max = 50) List<@Valid PurchaseLine> lines,
            @Size(max = 200) String note,
            Long version) {
    }

    public record CostView(long id, LocalDate date, BigDecimal usd, BigDecimal rate, BigDecimal bdt, String note,
            String addedBy, Instant createdAt, long version) {
    }

    public record PurchaseView(long id, LocalDate date, BigDecimal usd, BigDecimal bdt, BigDecimal rate,
            List<PurchaseLine> lines, String note, String addedBy, Instant createdAt, long version) {
    }

    /**
     * The cards above the lists. Spending and buying follow the dates chosen; the average rate and the balance are
     * always all-time, because dollars bought last month are still there to spend. The buying figures are left out
     * for anyone who may not see purchases.
     *
     * @param averageRate what a dollar has cost on average, bought ÷ dollars
     * @param balanceUsd  dollars bought minus dollars spent; negative when more was spent than was recorded as bought
     */
    public record Summary(BigDecimal spentUsd, BigDecimal spentBdt, long costCount, BigDecimal boughtUsd,
            BigDecimal boughtBdt, BigDecimal averageRate, BigDecimal balanceUsd) {
    }

    /** @param rate the average buying rate, to start the Add cost form; empty until something was bought */
    public record SuggestedRate(BigDecimal rate) {
    }
}
