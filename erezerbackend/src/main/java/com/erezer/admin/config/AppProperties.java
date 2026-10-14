package com.erezer.admin.config;

import jakarta.validation.constraints.NotBlank;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * General application settings ({@code app.*}).
 *
 * @param timeZone business time zone used for "today", date filters and printed dates
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotBlank String timeZone) {

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }
}
