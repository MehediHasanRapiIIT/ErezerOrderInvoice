package com.erezer.admin.courier;

import com.erezer.admin.courier.SteadfastClient.Credentials;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backup for missed webhooks (and the only sync when the panel has no public URL yet): every 10 minutes it
 * re-checks bookings that are still in flight and haven't changed for 2 hours, and confirms bookings whose
 * outcome was unknown. Steadfast caches statuses for 60 s, so polling more often gains nothing.
 */
@Component
class CourierPoller {

    private static final Logger log = LoggerFactory.getLogger(CourierPoller.class);
    private static final Duration CONFIRM_AFTER = Duration.ofMinutes(2);
    private static final Duration GIVE_UP_CONFIRMING_AFTER = Duration.ofMinutes(30);
    private static final Duration STALE_AFTER = Duration.ofHours(2);
    private static final int BATCH = 50;

    private final CourierShipmentRepository shipments;
    private final CourierService courierService;
    private final CourierBatchService batchService;
    private final CourierSettingsService settings;
    private final Clock clock;

    CourierPoller(CourierShipmentRepository shipments, CourierService courierService, CourierBatchService batchService,
            CourierSettingsService settings, Clock clock) {
        this.shipments = shipments;
        this.courierService = courierService;
        this.batchService = batchService;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT10M")
    void sync() {
        batchService.recoverInterrupted();
        Credentials credentials = settings.credentialsForSync();
        if (credentials == null) {
            return;
        }
        Instant now = clock.instant();
        List<CourierShipment> due = shipments.findDueForSync(now.minus(CONFIRM_AFTER), now.minus(STALE_AFTER),
                List.copyOf(CourierStatusSync.FINAL), Limit.of(BATCH));
        for (CourierShipment shipment : due) {
            try {
                boolean known = courierService.resolve(shipment, credentials, CourierEvent.Source.POLL);
                if (!known && shipment.getCreatedAt().isBefore(now.minus(GIVE_UP_CONFIRMING_AFTER))) {
                    // Steadfast never received it: release the order so staff can send it again.
                    log.warn("Booking for order {} was never confirmed by Steadfast; releasing it", shipment.getOrderId());
                    shipments.delete(shipment);
                }
            } catch (SteadfastException ex) {
                log.warn("Courier sync failed for shipment {}: {}", shipment.getId(), ex.getMessage());
                if (ex.kind() == SteadfastException.Kind.AUTH || ex.kind() == SteadfastException.Kind.RATE_LIMITED) {
                    // Never hammer Steadfast with bad keys or while it is throttling us (lockout after 10 auth
                    // failures in 5 minutes); the next run in 10 minutes tries again.
                    return;
                }
            } catch (RuntimeException ex) {
                log.error("Courier sync error for shipment {}", shipment.getId(), ex);
            }
        }
    }
}
