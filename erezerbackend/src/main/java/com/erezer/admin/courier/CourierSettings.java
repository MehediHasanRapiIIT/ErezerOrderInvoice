package com.erezer.admin.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Single-row settings for the Steadfast integration. Secrets (API keys, webhook token) are encrypted, never stored in clear. */
@Entity
@Table(name = "courier_settings")
public class CourierSettings {

    static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "base_url", nullable = false, length = 200)
    private String baseUrl;

    @Column(name = "api_key_enc")
    private String apiKeyEnc;

    @Column(name = "secret_key_enc")
    private String secretKeyEnc;

    @Column(name = "webhook_token_enc")
    private String webhookTokenEnc;

    @Column(name = "default_delivery_type", nullable = false)
    private short defaultDeliveryType;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CourierSettings() {
        // for JPA
    }

    boolean hasCredentials() {
        return apiKeyEnc != null && secretKeyEnc != null;
    }

    void update(boolean enabled, String baseUrl, int defaultDeliveryType, Long by, Instant now) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.defaultDeliveryType = (short) defaultDeliveryType;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    void setCredentials(String apiKeyEnc, String secretKeyEnc) {
        this.apiKeyEnc = apiKeyEnc;
        this.secretKeyEnc = secretKeyEnc;
    }

    void setWebhookTokenEnc(String encrypted) {
        this.webhookTokenEnc = encrypted;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    String getApiKeyEnc() {
        return apiKeyEnc;
    }

    String getSecretKeyEnc() {
        return secretKeyEnc;
    }

    String getWebhookTokenEnc() {
        return webhookTokenEnc;
    }

    int getDefaultDeliveryType() {
        return defaultDeliveryType;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
