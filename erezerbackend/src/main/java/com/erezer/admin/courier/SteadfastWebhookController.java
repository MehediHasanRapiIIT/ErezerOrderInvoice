package com.erezer.admin.courier;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives Steadfast's status callbacks. Configure it in the Steadfast panel (Webhook page) with the callback URL
 * {@code https://<your-domain>/api/webhooks/steadfast} and the token generated in Courier settings.
 *
 * <p>Every call carries the token as {@code Authorization: Bearer} and an {@code X-Signature} header, the hex
 * HMAC-SHA256 of the raw body keyed with the token; both are checked.
 *
 * <p>Steadfast waits 5 s for a 2xx and retries 5xx/no-response twice; 4xx is never retried. So: wrong token or
 * signature → 401, malformed body → 400, account events (payment_request, user_update…) and parcels we don't know
 * (booked outside the panel) → 200 "ignored", duplicates → 200.
 */
@RestController
class SteadfastWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SteadfastWebhookController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The parcel events this panel acts on; the rest are account-level and acknowledged without action. */
    private static final java.util.Set<String> PARCEL_EVENTS = java.util.Set.of("delivery_status", "tracking_update");

    private final CourierSettingsService settings;
    private final CourierShipmentRepository shipments;
    private final CourierStatusSync sync;

    SteadfastWebhookController(CourierSettingsService settings, CourierShipmentRepository shipments, CourierStatusSync sync) {
        this.settings = settings;
        this.shipments = shipments;
        this.sync = sync;
    }

    @PostMapping("/api/webhooks/steadfast")
    ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : null;
        if (!settings.verifyWebhook(token, signature, body == null ? "" : body)) {
            log.warn("Steadfast webhook rejected: bad token or signature");
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(body == null ? "" : body);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON"));
        }
        String type = Optional.ofNullable(text(payload, "notification_type")).orElse("delivery_status");
        if (!PARCEL_EVENTS.contains(type)) {
            log.info("Steadfast webhook event {} acknowledged without action", type);
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }
        String consignmentId = text(payload, "consignment_id");
        if (consignmentId == null || !consignmentId.matches("\\d+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing or invalid consignment_id"));
        }
        String invoice = text(payload, "invoice");

        Optional<CourierShipment> shipment = shipments.findFirstByConsignmentId(consignmentId)
                .or(() -> invoice == null ? Optional.empty() : shipments.findFirstByInvoiceOrderByIdDesc(invoice));
        if (shipment.isEmpty()) {
            log.info("Steadfast webhook for unknown consignment {} ignored", consignmentId);
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        String status = "delivery_status".equals(type) ? text(payload, "status") : null;
        if ("delivery_status".equals(type) && status == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing status"));
        }
        String dedupe = "steadfast:" + (idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey.trim() : CourierSettingsService.sha256(body));
        try {
            sync.apply(shipment.get().getId(), new CourierStatusSync.Update(status, text(payload, "tracking_message"),
                    amount(payload, "delivery_charge"), consignmentId), CourierEvent.Source.WEBHOOK, dedupe, body);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // Two deliveries of the same event raced; the first one won.
        }
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString().trim();
    }

    private static BigDecimal amount(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
