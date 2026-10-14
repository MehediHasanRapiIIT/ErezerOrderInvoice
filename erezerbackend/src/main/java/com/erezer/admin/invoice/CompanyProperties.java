package com.erezer.admin.invoice;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Seller details printed on invoices ({@code COMPANY_*} environment variables). */
@Validated
@ConfigurationProperties(prefix = "app.company")
public record CompanyProperties(
        @NotBlank String name,
        String address,
        String phone,
        String email,
        String invoiceFooter) {
}
