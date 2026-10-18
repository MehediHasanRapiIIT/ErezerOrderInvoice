package com.erezer.admin.courier;

import com.erezer.admin.courier.CourierDtos.BatchRequest;
import com.erezer.admin.courier.CourierDtos.BatchView;
import com.erezer.admin.courier.CourierDtos.PreviewRequest;
import com.erezer.admin.courier.CourierDtos.PreviewResponse;
import com.erezer.admin.courier.CourierDtos.SendRequest;
import com.erezer.admin.courier.CourierDtos.SendResult;
import com.erezer.admin.courier.CourierDtos.ShipmentView;
import com.erezer.admin.courier.CourierSettingsService.Availability;
import com.erezer.admin.courier.CourierSettingsService.SettingsView;
import com.erezer.admin.courier.CourierSettingsService.UpdateRequest;
import com.erezer.admin.courier.ParcelPlanner.Overrides;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CourierController {

    private final CourierSettingsService settings;
    private final CourierService courierService;
    private final CourierBatchService batchService;

    CourierController(CourierSettingsService settings, CourierService courierService, CourierBatchService batchService) {
        this.settings = settings;
        this.courierService = courierService;
        this.batchService = batchService;
    }

    // ---- Settings (admin) ---------------------------------------------------------------------------

    @GetMapping("/api/courier/settings")
    @PreAuthorize("hasAuthority('COURIER_SETTINGS')")
    SettingsView getSettings() {
        return settings.view();
    }

    @PutMapping("/api/courier/settings")
    @PreAuthorize("hasAuthority('COURIER_SETTINGS')")
    SettingsView updateSettings(@RequestBody UpdateRequest request) {
        return settings.update(request, CurrentUser.id());
    }

    @PostMapping("/api/courier/settings/test")
    @PreAuthorize("hasAuthority('COURIER_SETTINGS')")
    Map<String, BigDecimal> testConnection() {
        return Map.of("balance", settings.testConnection());
    }

    /** Generates a token and returns it once (it is stored encrypted and never shown again). */
    @PostMapping("/api/courier/settings/webhook-token")
    @PreAuthorize("hasAuthority('COURIER_SETTINGS')")
    Map<String, String> regenerateWebhookToken() {
        return Map.of("token", settings.regenerateWebhookToken());
    }

    record WebhookTokenRequest(String token) {
    }

    /** Saves a token generated in Steadfast's own Webhook page. */
    @PutMapping("/api/courier/settings/webhook-token")
    @PreAuthorize("hasAuthority('COURIER_SETTINGS')")
    org.springframework.http.ResponseEntity<Void> setWebhookToken(@RequestBody WebhookTokenRequest request) {
        settings.setWebhookToken(request.token());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** Lets the UI decide whether to show "Send to Steadfast". */
    @GetMapping("/api/courier/availability")
    Availability availability() {
        return settings.availability();
    }

    // ---- Sending --------------------------------------------------------------------------------------

    @PostMapping("/api/courier/preview")
    @PreAuthorize("hasAuthority('COURIER_SEND')")
    PreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
        return courierService.preview(request.orderIds());
    }

    @GetMapping("/api/courier/fraud-check")
    @PreAuthorize("hasAuthority('COURIER_SEND')")
    SteadfastClient.FraudReport fraudCheck(@org.springframework.web.bind.annotation.RequestParam String phone) {
        return courierService.fraudCheck(phone);
    }

    @PostMapping("/api/orders/{id}/courier")
    @PreAuthorize("hasAuthority('COURIER_SEND')")
    ResponseEntity<SendResult> send(@PathVariable long id, @Valid @RequestBody SendRequest request) {
        SendResult result = courierService.send(id, new Overrides(request.codAmount(), request.deliveryType(),
                request.note(), request.itemDescription()), CurrentUser.id());
        return "CONFIRMING".equals(result.state()) ? ResponseEntity.accepted().body(result) : ResponseEntity.ok(result);
    }

    @GetMapping("/api/orders/{id}/courier")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    ResponseEntity<ShipmentView> shipment(@PathVariable long id) {
        return courierService.shipmentFor(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/orders/{id}/courier/refresh")
    @PreAuthorize("hasAnyAuthority('COURIER_SEND', 'ORDER_STATUS_UPDATE')")
    ShipmentView refresh(@PathVariable long id) {
        return courierService.refresh(id);
    }

    @PostMapping("/api/courier/batches")
    @PreAuthorize("hasAuthority('COURIER_SEND')")
    ResponseEntity<Map<String, Long>> startBatch(@Valid @RequestBody BatchRequest request) {
        return ResponseEntity.accepted().body(Map.of("batchId", batchService.start(request.items(), CurrentUser.id())));
    }

    @GetMapping("/api/courier/batches/{id}")
    @PreAuthorize("hasAuthority('COURIER_SEND')")
    BatchView batch(@PathVariable long id) {
        return batchService.get(id);
    }
}
