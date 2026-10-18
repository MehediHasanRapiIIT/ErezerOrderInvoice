package com.erezer.admin.courier;

import com.erezer.admin.courier.SteadfastException.Kind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thin client for the Steadfast (Packzy) merchant API v1. Authenticates with the {@code Api-Key} and
 * {@code Secret-Key} headers. Redirects are never followed (so the keys are never forwarded) and every call has
 * a hard timeout. No automatic retries here: callers decide, based on {@link SteadfastException.Kind}.
 */
@Component
class SteadfastClient {

    private static final Logger log = LoggerFactory.getLogger(SteadfastClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient http;

    SteadfastClient() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    record Credentials(String baseUrl, String apiKey, String secretKey) {
    }

    /** The parcel as Steadfast expects it (field names match the API). */
    record Parcel(String invoice, String recipientName, String recipientPhone, String recipientAddress,
            BigDecimal codAmount, String note, String itemDescription, int deliveryType) {

        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("invoice", invoice);
            body.put("recipient_name", recipientName);
            body.put("recipient_phone", recipientPhone);
            body.put("recipient_address", recipientAddress);
            body.put("cod_amount", codAmount);
            if (note != null) {
                body.put("note", note);
            }
            if (itemDescription != null) {
                body.put("item_description", itemDescription);
            }
            body.put("delivery_type", deliveryType);
            return body;
        }
    }

    record Booking(String consignmentId, String trackingCode, String trackingLink, String status,
            String requestJson, String responseJson) {
    }

    /** POST /create_order. Throws {@link SteadfastException} on any failure. */
    Booking createOrder(Credentials credentials, Parcel parcel) {
        String requestJson = JSON.writeValueAsString(parcel.toBody());
        JsonNode body = call(credentials, "POST", "/create_order", requestJson);
        JsonNode consignment = body.path("consignment");
        if (consignment.isMissingNode() || consignment.path("consignment_id").isMissingNode()) {
            throw new SteadfastException(Kind.UNEXPECTED, 200, "Steadfast did not return a consignment: " + abbreviate(body.toString()));
        }
        return new Booking(text(consignment, "consignment_id"), text(consignment, "tracking_code"),
                text(consignment, "tracking_link"), text(consignment, "status"), requestJson, body.toString());
    }

    /** GET /status_by_cid/{id}: the delivery status, or empty if Steadfast does not know the consignment. */
    Optional<String> statusByConsignment(Credentials credentials, String consignmentId) {
        return status(credentials, "/status_by_cid/" + encode(consignmentId));
    }

    /** GET /status_by_invoice/{invoice}: used to resolve bookings whose outcome is unknown. */
    Optional<String> statusByInvoice(Credentials credentials, String invoice) {
        return status(credentials, "/status_by_invoice/" + encode(invoice));
    }

    /** GET /ping (no keys): true when Steadfast answers at this base URL. Never throws. */
    boolean ping(String baseUrl) {
        try {
            return http.get().uri(baseUrl.replaceAll("/+$", "") + "/ping").accept(MediaType.APPLICATION_JSON)
                    .exchange((req, res) -> res.getStatusCode().is2xxSuccessful());
        } catch (RuntimeException ex) {
            log.warn("Steadfast ping to {} failed: {}", baseUrl, ex.getMessage());
            return false;
        }
    }

    /**
     * The customer's delivery record across Steadfast merchants. Ratios are 0-1, or null when Steadfast has too
     * little history to say (shown as "unknown", never as zero).
     */
    record FraudReport(BigDecimal deliveryRatio, BigDecimal cancellationRatio, Integer deliveredCount,
            Integer cancelledCount, Integer totalReports, String volumeBand, String volumeRange,
            java.util.List<String> fraudCategories) {
    }

    /** GET /fraud_check/score/{phone}. Limited per merchant by Steadfast: a 429 surfaces as RATE_LIMITED. */
    FraudReport fraudCheck(Credentials credentials, String phone) {
        JsonNode root = call(credentials, "GET", "/fraud_check/score/" + encode(phone), null);
        JsonNode d = root.path("data").isObject() ? root.path("data") : root;
        java.util.List<String> categories = new java.util.ArrayList<>();
        JsonNode cats = d.path("fraud_categories");
        if (cats.isArray()) {
            cats.forEach(c -> categories.add(c.isObject() ? Optional.ofNullable(text(c, "name")).orElse(c.toString()) : c.asString()));
        } else if (cats.isObject()) {
            cats.properties().forEach(e -> categories.add(e.getKey() + " (" + e.getValue().asString() + ")"));
        }
        return new FraudReport(decimal(d, "delivery_ratio"), decimal(d, "cancellation_ratio"), integer(d, "delivered_count"),
                integer(d, "cancelled_count"), integer(d, "total_reports"), text(d, "volume_band"), text(d, "volume_range"),
                java.util.List.copyOf(categories));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        return value == null ? null : value.intValue();
    }

    /** GET /get_balance: also serves as the "test connection" call. */
    BigDecimal balance(Credentials credentials) {
        JsonNode body = call(credentials, "GET", "/get_balance", null);
        JsonNode balance = body.path("current_balance");
        return balance.isMissingNode() || balance.isNull() ? BigDecimal.ZERO : new BigDecimal(balance.asString());
    }

    private Optional<String> status(Credentials credentials, String path) {
        try {
            JsonNode body = call(credentials, "GET", path, null);
            String status = text(body, "delivery_status");
            return Optional.ofNullable(status);
        } catch (SteadfastException ex) {
            if (ex.kind() == Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private JsonNode call(Credentials credentials, String method, String path, String jsonBody) {
        String url = credentials.baseUrl().replaceAll("/+$", "") + path;
        try {
            RestClient.RequestBodySpec request = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(url)
                    .header("Api-Key", credentials.apiKey())
                    .header("Secret-Key", credentials.secretKey())
                    .accept(MediaType.APPLICATION_JSON);
            if (jsonBody != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
            }
            return request.exchange((req, res) -> {
                int status = res.getStatusCode().value();
                String raw = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                return interpret(status, raw, path);
            });
        } catch (SteadfastException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.warn("Steadfast {} {} failed: {}", method, path, ex.getMessage());
            throw new SteadfastException(Kind.UNAVAILABLE, 0, "Steadfast could not be reached (timeout or network error)", ex);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof SteadfastException se) {
                throw se;
            }
            throw new SteadfastException(Kind.UNAVAILABLE, 0, "Steadfast call failed: " + ex.getMessage(), ex);
        }
    }

    /** Maps HTTP status and body to a result. Steadfast sometimes reports errors with HTTP 200 and a body status. */
    static JsonNode interpret(int httpStatus, String raw, String path) throws IOException {
        JsonNode body;
        try {
            body = raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (RuntimeException ex) {
            if (httpStatus >= 500 || httpStatus == 0) {
                throw new SteadfastException(Kind.UNAVAILABLE, httpStatus, "Steadfast returned an invalid response");
            }
            throw new SteadfastException(Kind.UNEXPECTED, httpStatus, "Steadfast returned non-JSON: " + abbreviate(raw));
        }
        String message = message(body);
        if (httpStatus == 401) {
            throw new SteadfastException(Kind.AUTH, 401, "Steadfast rejected the API keys (or the parcel belongs to another Steadfast profile). "
                    + "Check the keys in Courier settings. Repeated failures lock the account for an hour.");
        }
        if (httpStatus == 403) {
            throw new SteadfastException(Kind.FORBIDDEN, 403, "Steadfast refused the request (account inactive or KYC pending)" + suffix(message));
        }
        if (httpStatus == 404) {
            throw new SteadfastException(Kind.NOT_FOUND, 404, "Not found on Steadfast" + suffix(message));
        }
        if (httpStatus == 429) {
            throw new SteadfastException(Kind.RATE_LIMITED, 429, "Steadfast is refusing requests for now (rate limit, or a lockout after repeated "
                    + "key failures, which lasts up to an hour). Try again later.");
        }
        if (httpStatus >= 500) {
            throw new SteadfastException(Kind.UNAVAILABLE, httpStatus, "Steadfast server error" + suffix(message));
        }
        if (httpStatus >= 400) {
            throw new SteadfastException(Kind.VALIDATION, httpStatus, message == null ? "Steadfast rejected the request" : message);
        }
        int bodyStatus = body.path("status").asInt(200);
        if (bodyStatus >= 400) {
            Kind kind = bodyStatus == 404 ? Kind.NOT_FOUND : bodyStatus == 401 ? Kind.AUTH : Kind.VALIDATION;
            throw new SteadfastException(kind, bodyStatus, message == null ? "Steadfast rejected the request" : message);
        }
        return body;
    }

    /** Human-readable message from Steadfast's {"message": ...} or {"errors": {field: [msg]}} bodies. */
    private static String message(JsonNode body) {
        JsonNode errors = body.path("errors");
        if (errors.isObject() && !errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, JsonNode> e : errors.properties()) {
                String text = e.getValue().isArray() && !e.getValue().isEmpty() ? e.getValue().get(0).asString() : e.getValue().asString();
                sb.append(sb.isEmpty() ? "" : "; ").append(e.getKey()).append(": ").append(text);
            }
            return sb.toString();
        }
        String msg = text(body, "message");
        return msg == null || msg.isBlank() ? null : msg;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static String suffix(String message) {
        return message == null ? "" : ": " + message;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String abbreviate(String value) {
        return value == null ? "" : value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
