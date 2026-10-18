package com.erezer.admin.courier;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.courier.SteadfastClient.Credentials;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierSettingsService {

    private final CourierSettingsRepository repository;
    private final SecretCipher cipher;
    private final SteadfastClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Hosts that may be called over plain http (local testing only); everything else must be https. */
    private final java.util.Set<String> insecureHosts;

    CourierSettingsService(CourierSettingsRepository repository, SecretCipher cipher, SteadfastClient client, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${app.courier.allow-insecure-hosts:localhost,127.0.0.1}")
            java.util.List<String> insecureHosts) {
        this.insecureHosts = java.util.Set.copyOf(insecureHosts);
        this.repository = repository;
        this.cipher = cipher;
        this.client = client;
        this.clock = clock;
    }

    /** What the settings page shows. Secrets are never returned, only whether they are set (and the key's tail). */
    public record SettingsView(boolean enabled, String baseUrl, int defaultDeliveryType, String apiKeyHint,
            boolean secretKeySet, boolean webhookTokenSet, String webhookPath, Instant updatedAt) {
    }

    public record UpdateRequest(boolean enabled, String baseUrl, int defaultDeliveryType, String apiKey, String secretKey) {
    }

    /** Whether staff should see "Send to Steadfast" at all. */
    public record Availability(boolean enabled, int defaultDeliveryType) {
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        CourierSettings s = load();
        String hint = null;
        if (s.getApiKeyEnc() != null) {
            String key = cipher.decrypt(s.getApiKeyEnc());
            hint = "••••" + key.substring(Math.max(0, key.length() - 4));
        }
        return new SettingsView(s.isEnabled(), s.getBaseUrl(), s.getDefaultDeliveryType(), hint,
                s.getSecretKeyEnc() != null, s.getWebhookTokenEnc() != null, "/api/webhooks/steadfast", s.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public Availability availability() {
        CourierSettings s = load();
        return new Availability(s.isEnabled() && s.hasCredentials(), s.getDefaultDeliveryType());
    }

    @Transactional
    public SettingsView update(UpdateRequest request, long userId) {
        CourierSettings s = load();
        String baseUrl = validateBaseUrl(request.baseUrl());
        if (request.defaultDeliveryType() != 0 && request.defaultDeliveryType() != 1) {
            throw ApiException.badRequest("INVALID_DELIVERY_TYPE", "Delivery type must be home (0) or hub pickup (1)");
        }
        boolean newKey = request.apiKey() != null && !request.apiKey().isBlank();
        boolean newSecret = request.secretKey() != null && !request.secretKey().isBlank();
        if (newKey != newSecret && !s.hasCredentials()) {
            throw ApiException.badRequest("CREDENTIALS_INCOMPLETE", "Enter both the API key and the secret key");
        }
        if (newKey || newSecret) {
            s.setCredentials(newKey ? cipher.encrypt(request.apiKey().trim()) : s.getApiKeyEnc(),
                    newSecret ? cipher.encrypt(request.secretKey().trim()) : s.getSecretKeyEnc());
        }
        if (request.enabled() && !s.hasCredentials()) {
            throw ApiException.badRequest("CREDENTIALS_REQUIRED", "Add the API key and secret key before enabling");
        }
        s.update(request.enabled(), baseUrl, request.defaultDeliveryType(), userId, clock.instant());
        return view();
    }

    /** Calls Steadfast's balance endpoint with the saved keys. */
    @Transactional(readOnly = true)
    public BigDecimal testConnection() {
        CourierSettings s = load();
        if (!s.hasCredentials()) {
            throw ApiException.badRequest("CREDENTIALS_REQUIRED", "Save the API key and secret key first");
        }
        // /ping needs no keys: if it fails the problem is the address or the network, and a bad key isn't
        // spent on a call that counts towards Steadfast's lockout (10 failures in 5 minutes).
        if (!client.ping(s.getBaseUrl())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "COURIER_UNREACHABLE",
                    "Steadfast could not be reached at " + s.getBaseUrl() + ". Check the API URL and the server's internet access.");
        }
        try {
            return client.balance(credentials(s));
        } catch (SteadfastException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "COURIER_" + ex.kind(), ex.getMessage());
        }
    }

    /** Creates a new webhook token to paste into Steadfast's Webhook page. Stored encrypted; shown only now. */
    @Transactional
    public String regenerateWebhookToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        load().setWebhookTokenEnc(cipher.encrypt(token));
        return token;
    }

    /** Saves a token generated in Steadfast's panel instead (Steadfast allows letters, digits and - _ . =). */
    @Transactional
    public void setWebhookToken(String token) {
        String value = token == null ? "" : token.trim();
        if (!value.matches("[A-Za-z0-9._=-]{16,200}")) {
            throw ApiException.badRequest("INVALID_WEBHOOK_TOKEN",
                    "The token must be 16-200 characters of letters, digits and - _ . =");
        }
        load().setWebhookTokenEnc(cipher.encrypt(value));
    }

    /**
     * Verifies a webhook the way Steadfast documents it: the Bearer token must match, and X-Signature must equal the
     * hex HMAC-SHA256 of the raw body keyed with the token. Both comparisons are constant-time.
     */
    @Transactional(readOnly = true)
    public boolean verifyWebhook(String bearerToken, String signature, String rawBody) {
        String stored = load().getWebhookTokenEnc();
        if (stored == null || bearerToken == null || signature == null || rawBody == null) {
            return false;
        }
        String token = cipher.decrypt(stored);
        boolean tokenOk = MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), bearerToken.getBytes(StandardCharsets.UTF_8));
        String expected = hmacSha256Hex(token, rawBody);
        boolean signatureOk = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        return tokenOk && signatureOk;
    }

    static String hmacSha256Hex(String key, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Credentials for an API call; fails if the integration is off or not configured. */
    @Transactional(readOnly = true)
    Credentials requireCredentials() {
        CourierSettings s = load();
        if (!s.isEnabled() || !s.hasCredentials()) {
            throw ApiException.conflict("COURIER_DISABLED", "The Steadfast integration is not enabled. An admin can turn it on in Courier settings.");
        }
        return credentials(s);
    }

    /** Credentials even when sending is disabled, for syncing parcels that were already booked. */
    @Transactional(readOnly = true)
    Credentials credentialsForSync() {
        CourierSettings s = load();
        return s.hasCredentials() ? credentials(s) : null;
    }

    private Credentials credentials(CourierSettings s) {
        return new Credentials(s.getBaseUrl(), cipher.decrypt(s.getApiKeyEnc()), cipher.decrypt(s.getSecretKeyEnc()));
    }

    private CourierSettings load() {
        return repository.findById(CourierSettings.SINGLETON_ID).orElseThrow();
    }

    /** HTTPS only (keys travel in headers), except plain HTTP to localhost for local testing. */
    private String validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost();
            boolean local = host != null && insecureHosts.contains(host);
            if (host == null || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))) {
                throw new IllegalArgumentException();
            }
            return uri.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_BASE_URL", "The API URL must be an https:// address");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
