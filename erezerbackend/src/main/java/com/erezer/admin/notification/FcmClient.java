package com.erezer.admin.notification;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Sends messages through Firebase Cloud Messaging's HTTP API, signing in with the service account. */
@Component
public class FcmClient {

    public enum Result {
        SENT,
        /** The phone or browser no longer accepts messages, so its token should be forgotten. */
        INVALID_TOKEN,
        FAILED
    }

    /** A Firebase connection. */
    public record Credentials(String projectId, String clientEmail, String privateKeyPem) {
    }

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TOKEN_LIFE = Duration.ofMinutes(55);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private record AccessToken(String clientEmail, String value, Instant expires) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Clock clock;
    private volatile AccessToken accessToken;

    FcmClient(Clock clock) {
        this.clock = clock;
    }

    public Result send(Credentials credentials, String deviceToken, Map<String, String> data) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", deviceToken);
            message.put("data", data);
            message.put("webpush", Map.of("headers", Map.of("Urgency", "high", "TTL", "86400")));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + credentials.projectId() + "/messages:send"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token(credentials))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("message", message))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 300) {
                return Result.SENT;
            }
            if (response.statusCode() == 404 || (response.statusCode() == 400 && response.body().contains("registration token"))) {
                return Result.INVALID_TOKEN;
            }
            log.warn("Firebase refused a message: HTTP {}", response.statusCode());
            return Result.FAILED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception ex) {
            log.warn("Could not reach Firebase: {}", ex.toString());
            return Result.FAILED;
        }
    }

    private String token(Credentials credentials) throws Exception {
        AccessToken current = accessToken;
        Instant now = clock.instant();
        if (current != null && current.clientEmail().equals(credentials.clientEmail()) && now.isBefore(current.expires())) {
            return current.value();
        }
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + assertion(credentials, now);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Firebase refused the service account: HTTP " + response.statusCode());
        }
        String value = JSON.readTree(response.body()).path("access_token").asString();
        accessToken = new AccessToken(credentials.clientEmail(), value, now.plus(TOKEN_LIFE));
        return value;
    }

    /** The signed request Google exchanges for an access token. */
    static String assertion(Credentials credentials, Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", TOKEN_URL);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(Duration.ofHours(1)).getEpochSecond());
        String unsigned = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64(JSON.writeValueAsBytes(claims));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(credentials.privateKeyPem()));
            signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
            return unsigned + "." + base64(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The Firebase private key can't be used", ex);
        }
    }

    /** @throws GeneralSecurityException when the text isn't a usable key */
    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (IllegalArgumentException ex) {
            throw new GeneralSecurityException("Not a private key", ex);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
