package com.erezer.admin.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class CourierIntegrationTest extends IntegrationTest {

    private static FakeSteadfast steadfast;

    @Autowired
    JdbcClient jdbc;

    private String admin;
    private String webhookToken;

    @BeforeAll
    static void startFake() throws Exception {
        steadfast = new FakeSteadfast();
    }

    @AfterAll
    static void stopFake() {
        steadfast.close();
    }

    @BeforeEach
    void configure() throws Exception {
        admin = adminBearer();
        steadfast.mode = FakeSteadfast.Mode.OK;
        steadfast.rejectInvoices.clear();
        steadfast.existingInvoices.clear();
        mvc.perform(put("/api/courier/settings").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"baseUrl":"%s","defaultDeliveryType":0,
                                 "apiKey":"test-api-key-1234","secretKey":"test-secret"}""".formatted(steadfast.baseUrl())))
                .andExpect(status().isOk());
        MvcResult token = mvc.perform(post("/api/courier/settings/webhook-token").header("Authorization", admin)).andReturn();
        webhookToken = read(token, "$.token");
    }

    @Test
    void settingsNeverExposeSecretsAndTestConnectionShowsBalance() throws Exception {
        mvc.perform(get("/api/courier/settings").header("Authorization", admin))
                .andExpect(jsonPath("$.apiKeyHint").value("••••1234"))
                .andExpect(jsonPath("$.secretKeySet").value(true))
                .andExpect(jsonPath("$.secretKey").doesNotExist())
                .andExpect(jsonPath("$.webhookTokenSet").value(true));
        mvc.perform(post("/api/courier/settings/test").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1520.5));
        // Stored encrypted, not in clear.
        String stored = jdbc.sql("select api_key_enc from courier_settings").query(String.class).single();
        assertThat(stored).doesNotContain("test-api-key");
        mvc.perform(put("/api/courier/settings").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"baseUrl\":\"http://evil.example.com\",\"defaultDeliveryType\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BASE_URL"));
    }

    @Test
    void previewBuildsTheParcelAndListsEveryProblem() throws Exception {
        long cash = acceptedOrder("CASH", "01711000001", 1000);
        long prepaid = acceptedOrder("BKASH", "01711000002", 800);
        long badPhone = acceptedOrder("CASH", "02-9876543", 500);
        long placed = createOrder("CASH", "01711000003", 500);

        mvc.perform(post("/api/courier/preview").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[%d,%d,%d,%d]}".formatted(cash, prepaid, badPhone, placed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(2))
                .andExpect(jsonPath("$.totalCod").value(1060.0))                 // 1000 + 60 delivery; prepaid = 0
                .andExpect(jsonPath("$.parcels[0].codAmount").value(1060.0))
                .andExpect(jsonPath("$.parcels[0].itemDescription").value("T-Shirt (L) ×1"))
                .andExpect(jsonPath("$.parcels[1].codAmount").value(0))
                .andExpect(jsonPath("$.parcels[2].problems[0]").value(startsWith("Phone must be a Bangladeshi mobile")))
                .andExpect(jsonPath("$.parcels[3].blockedReason").value("Accept the order before sending it to the courier"));
    }

    @Test
    void sendBooksTheParcelAndShipsTheOrder() throws Exception {
        long id = acceptedOrder("CASH", "+880 1711-000004", 1200);

        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Call before delivery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BOOKED"))
                .andExpect(jsonPath("$.shipment.consignmentId").exists())
                .andExpect(jsonPath("$.shipment.trackingCode").value(startsWith("SF")));

        FakeSteadfast.Received sent = steadfast.lastCreate();
        assertThat(sent.apiKey()).isEqualTo("test-api-key-1234");
        assertThat(sent.secretKey()).isEqualTo("test-secret");
        assertThat(sent.body()).contains("\"recipient_phone\":\"01711000004\"", "\"cod_amount\":1260",
                "\"note\":\"Call before delivery\"", "\"delivery_type\":0");

        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.courier").value("Steadfast"))
                .andExpect(jsonPath("$.trackingNumber").value(startsWith("SF")));

        // A second click (or a concurrent bulk send) must not book a second parcel.
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SENT"));
    }

    @Test
    void steadfastValidationErrorReleasesTheOrderForCorrection() throws Exception {
        long id = acceptedOrder("CASH", "01711000005", 900);
        steadfast.mode = FakeSteadfast.Mode.VALIDATION_ERROR;
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("recipient_address: The recipient address field is required."));
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.status").value("ACCEPTED"));

        steadfast.mode = FakeSteadfast.Mode.OK;
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownOutcomeIsConfirmedByInvoiceWithoutDoubleBooking() throws Exception {
        long id = acceptedOrder("CASH", "01711000006", 700);
        steadfast.mode = FakeSteadfast.Mode.SERVER_ERROR; // Steadfast books it but our reply is lost
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CONFIRMING"));
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()); // retrying is blocked while confirming

        steadfast.mode = FakeSteadfast.Mode.OK;
        mvc.perform(post("/api/orders/" + id + "/courier/refresh").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BOOKED"));
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.status").value("SHIPPED"));
        long creates = steadfast.requests.stream().filter(r -> r.path().equals("/create_order") && r.body().contains(orderNumber(id))).count();
        assertThat(creates).isEqualTo(1);
    }

    @Test
    void webhookUpdatesOrdersAndRecordsTheRealCourierCharge() throws Exception {
        long delivered = shippedViaSteadfast("01711000007", 1000);
        long refused = shippedViaSteadfast("01711000008", 1500);
        String cidDelivered = consignment(delivered);
        String cidRefused = consignment(refused);

        webhook("wrong-token", "{\"consignment_id\":" + cidDelivered + ",\"status\":\"delivered\"}", null)
                .andExpect(status().isUnauthorized());

        String body = "{\"notification_type\":\"delivery_status\",\"consignment_id\":%s,\"invoice\":\"%s\",\"status\":\"delivered\","
                .formatted(cidDelivered, orderNumber(delivered))
                + "\"cod_amount\":1060,\"delivery_charge\":50,\"tracking_message\":\"Delivered to customer\",\"updated_at\":\"2026-10-18 12:00:00\"}";
        webhook(webhookToken, body, "evt-1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"));
        webhook(webhookToken, body, "evt-1").andExpect(status().isOk()); // retried delivery: ignored

        mvc.perform(get("/api/orders/" + delivered).header("Authorization", admin))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.history[-1].note").value("Steadfast: delivered · Delivered to customer"));
        assertThat(jdbc.sql("select courier_charge from orders where id = :id").param("id", delivered)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("50");
        assertThat(jdbc.sql("select count(*) from courier_events where dedupe_key = 'steadfast:evt-1'").query(Long.class).single()).isEqualTo(1);

        webhook(webhookToken, "{\"consignment_id\":" + cidRefused + ",\"status\":\"cancelled\",\"delivery_charge\":120}", "evt-2")
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + refused).header("Authorization", admin)).andExpect(jsonPath("$.status").value("RETURNED"));

        webhook(webhookToken, "{\"consignment_id\":123456789,\"status\":\"delivered\"}", "evt-3")
                .andExpect(jsonPath("$.status").value("ignored"));
        webhook(webhookToken, "{\"status\":\"delivered\"}", "evt-4").andExpect(status().isBadRequest());
    }

    @Test
    void partialDeliveryIsFlaggedNotGuessed() throws Exception {
        long id = shippedViaSteadfast("01711000009", 1000);
        webhook(webhookToken, "{\"consignment_id\":" + consignment(id) + ",\"status\":\"partial_delivered\"}", null)
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(get("/api/orders/" + id + "/courier").header("Authorization", admin))
                .andExpect(jsonPath("$.courierStatus").value("partial_delivered"))
                .andExpect(jsonPath("$.needsAttention").value(true));
    }

    @Test
    void bulkSendBooksEachOrderAndReportsFailuresIndividually() throws Exception {
        long a = acceptedOrder("CASH", "01711000010", 500);
        long b = acceptedOrder("NAGAD", "01711000011", 600);
        long c = acceptedOrder("CASH", "01711000012", 700);
        steadfast.rejectInvoices.add(orderNumber(c));

        MvcResult started = mvc.perform(post("/api/courier/batches").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderId\":%d},{\"orderId\":%d,\"codAmount\":100},{\"orderId\":%d}]}".formatted(a, b, c)))
                .andExpect(status().isAccepted())
                .andReturn();
        long batchId = ((Number) read(started, "$.batchId")).longValue();

        String state = "RUNNING";
        MvcResult batch = null;
        for (int i = 0; i < 100 && state.equals("RUNNING"); i++) {
            Thread.sleep(100);
            batch = mvc.perform(get("/api/courier/batches/" + batchId).header("Authorization", admin)).andReturn();
            state = read(batch, "$.status");
        }
        assertThat(state).isEqualTo("COMPLETED");
        assertThat((Integer) read(batch, "$.succeeded")).isEqualTo(2);
        assertThat((Integer) read(batch, "$.failed")).isEqualTo(1);
        assertThat((String) read(batch, "$.items[2].message")).contains("recipient address");
        assertThat(steadfast.requests.stream().anyMatch(r -> r.body().contains(orderNumber(b)) && r.body().contains("\"cod_amount\":100"))).isTrue();
        mvc.perform(get("/api/orders/" + a).header("Authorization", admin)).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(get("/api/orders/" + c).header("Authorization", admin)).andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void permissionsAreEnforced() throws Exception {
        long id = acceptedOrder("CASH", "01711000013", 500);
        String viewer = bearer(Role.MODERATOR, Permission.ORDER_VIEW);
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        String sender = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.COURIER_SEND, Permission.COURIER_SETTINGS);
        mvc.perform(get("/api/courier/settings").header("Authorization", sender)).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", sender)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    }

    @Test
    void dashboardUsesTheCourierChargeOnceKnown() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Dhaka"));
        MvcResult before = mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)
                .param("from", today.toString()).param("to", today.toString())).andReturn();
        double diffBefore = ((Number) read(before, "$.current.deliveryChargeDifference")).doubleValue();
        double profitBefore = ((Number) read(before, "$.current.netProfit")).doubleValue();

        long id = shippedViaSteadfast("01711000014", 1000); // delivery charge 60
        webhook(webhookToken, "{\"consignment_id\":" + consignment(id) + ",\"status\":\"delivered\",\"delivery_charge\":45}", null);

        MvcResult after = mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)
                .param("from", today.toString()).param("to", today.toString())).andReturn();
        assertThat(((Number) read(after, "$.current.deliveryChargeDifference")).doubleValue() - diffBefore).isEqualTo(15.0);
        // +1000 product revenue, +15 because the courier charged 45 instead of the 60 we collected
        assertThat(((Number) read(after, "$.current.netProfit")).doubleValue() - profitBefore).isEqualTo(1015.0);
    }

    @Test
    void webhookRequiresAValidSignatureAndIgnoresAccountEvents() throws Exception {
        long id = shippedViaSteadfast("01711000015", 1000);
        String body = "{\"notification_type\":\"delivery_status\",\"consignment_id\":" + consignment(id) + ",\"status\":\"delivered\"}";
        webhook(webhookToken, null, body, null).andExpect(status().isUnauthorized());                    // no signature
        webhook(webhookToken, "ab".repeat(32), body, null).andExpect(status().isUnauthorized());        // wrong signature
        webhook(webhookToken, CourierSettingsService.hmacSha256Hex(webhookToken, body + " "), body, null)
                .andExpect(status().isUnauthorized());                                                   // signed a different body
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.status").value("SHIPPED"));

        webhook(webhookToken, "{\"notification_type\":\"payment_request\",\"amount\":5000}", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));

        // A token generated in Steadfast's own panel can be pasted instead.
        String pasted = "SF-token_from.panel=" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(put("/api/courier/settings/webhook-token").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + pasted + "\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/api/courier/settings/webhook-token").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_TOKEN"));
        webhook(webhookToken, body, null).andExpect(status().isUnauthorized()); // the old token no longer works
        webhook(pasted, body, null).andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.status").value("DELIVERED"));
        String stored = jdbc.sql("select webhook_token_enc from courier_settings").query(String.class).single();
        assertThat(stored).doesNotContain(pasted);
    }

    @Test
    void bulkSendStopsOnRejectedKeysToAvoidALockout() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            items.append(i == 0 ? "" : ",").append("{\"orderId\":").append(acceptedOrder("CASH", "0171100002" + i, 500)).append('}');
        }
        long before = steadfast.requests.stream().filter(r -> r.path().equals("/create_order")).count();
        steadfast.mode = FakeSteadfast.Mode.AUTH_ERROR;
        MvcResult started = mvc.perform(post("/api/courier/batches").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isAccepted()).andReturn();
        MvcResult batch = awaitBatch(((Number) read(started, "$.batchId")).longValue());
        assertThat((Integer) read(batch, "$.failed")).isEqualTo(8);
        long calls = steadfast.requests.stream().filter(r -> r.path().equals("/create_order")).count() - before;
        assertThat(calls).isLessThanOrEqualTo(4); // only the parcels already in flight; the rest were never tried
        assertThat((String) read(batch, "$.items[7].message"))
                .startsWith("Not sent: the batch was stopped because Steadfast rejected the API keys");
    }

    @Test
    void anInvoiceSteadfastAlreadyHasIsLinkedNotRebooked() throws Exception {
        long id = acceptedOrder("CASH", "01711000016", 800);
        steadfast.existingInvoices.add(orderNumber(id));
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BOOKED"))
                .andExpect(jsonPath("$.message").value(startsWith("Steadfast already had a parcel")));
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void parcelTextIsCleanedAndCodMayHavePaisa() throws Exception {
        long id = acceptedOrder("CASH", "01711000017", 500);
        jdbc.sql("update orders set customer_address = 'House {7}; Road <2> $ Mirpur' where id = :id").param("id", id).update();
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codAmount\":560.50,\"note\":\"Gate <B>\"}"))
                .andExpect(status().isOk());
        assertThat(steadfast.lastCreate().body()).contains("\"recipient_address\":\"House 7 Road 2 Mirpur\"",
                "\"cod_amount\":560.5", "\"note\":\"Gate B\"");

        long other = acceptedOrder("CASH", "01711000018", 500);
        mvc.perform(post("/api/orders/" + other + "/courier").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codAmount\":560.555}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("COD amount can have at most 2 decimal places"));
    }

    @Test
    void fraudCheckReportsTheRecordAndHandlesTheLimit() throws Exception {
        mvc.perform(get("/api/courier/fraud-check").param("phone", "+880 1711-000019").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryRatio").value(0.82))
                .andExpect(jsonPath("$.cancellationRatio").doesNotExist())
                .andExpect(jsonPath("$.deliveredCount").value(23))
                .andExpect(jsonPath("$.fraudCategories[0]").value("fake_order"));
        assertThat(steadfast.requests.get(steadfast.requests.size() - 1).path()).isEqualTo("/fraud_check/score/01711000019");
        mvc.perform(get("/api/courier/fraud-check").param("phone", "12345").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        steadfast.mode = FakeSteadfast.Mode.RATE_LIMITED;
        mvc.perform(get("/api/courier/fraud-check").param("phone", "01711000019").header("Authorization", admin))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COURIER_RATE_LIMITED"));
        mvc.perform(get("/api/courier/fraud-check").param("phone", "01711000019")
                .header("Authorization", bearer(Role.MODERATOR, Permission.ORDER_VIEW))).andExpect(status().isForbidden());
    }

    @Test
    void testConnectionTellsAnUnreachableServerFromRejectedKeys() throws Exception {
        steadfast.mode = FakeSteadfast.Mode.DOWN;
        mvc.perform(post("/api/courier/settings/test").header("Authorization", admin))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("COURIER_UNREACHABLE"));
        steadfast.mode = FakeSteadfast.Mode.AUTH_ERROR;
        mvc.perform(post("/api/courier/settings/test").header("Authorization", admin))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("COURIER_AUTH"));
    }

    // ---- helpers ----

    private MvcResult awaitBatch(long batchId) throws Exception {
        String state = "RUNNING";
        MvcResult batch = null;
        for (int i = 0; i < 100 && state.equals("RUNNING"); i++) {
            Thread.sleep(100);
            batch = mvc.perform(get("/api/courier/batches/" + batchId).header("Authorization", admin)).andReturn();
            state = read(batch, "$.status");
        }
        assertThat(state).isEqualTo("COMPLETED");
        return batch;
    }

    /** A webhook signed the way Steadfast signs it (X-Signature = hex HMAC-SHA256 of the body, keyed with the token). */
    private ResultActions webhook(String token, String body, String idempotencyKey) throws Exception {
        return webhook(token, CourierSettingsService.hmacSha256Hex(token, body), body, idempotencyKey);
    }

    private ResultActions webhook(String token, String signature, String body, String idempotencyKey) throws Exception {
        var request = post("/api/webhooks/steadfast").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token);
        if (signature != null) {
            request.header("X-Signature", signature);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private long createOrder(String payment, String phone, int price) throws Exception {
        String json = """
                {"paymentMethod":"%s","deliveryCharge":60,
                 "customer":{"name":"Courier Test %s","phone":"%s","address":"House 1, Road 2, Mirpur, Dhaka"},
                 "items":[{"productName":"T-Shirt","productCode":"TS-1","size":"L","unitPrice":%d,"quantity":1,"imageIds":[]}]}
                """.formatted(payment, UUID.randomUUID().toString().substring(0, 4), phone, price);
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private long acceptedOrder(String payment, String phone, int price) throws Exception {
        long id = createOrder(payment, phone, price);
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}")).andExpect(status().isOk());
        return id;
    }

    private long shippedViaSteadfast(String phone, int price) throws Exception {
        long id = acceptedOrder("CASH", phone, price);
        mvc.perform(post("/api/orders/" + id + "/courier").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        return id;
    }

    private String consignment(long orderId) throws Exception {
        MvcResult r = mvc.perform(get("/api/orders/" + orderId + "/courier").header("Authorization", admin)).andReturn();
        return read(r, "$.consignmentId");
    }

    private String orderNumber(long orderId) {
        return jdbc.sql("select order_number from orders where id = :id").param("id", orderId).query(String.class).single();
    }
}
