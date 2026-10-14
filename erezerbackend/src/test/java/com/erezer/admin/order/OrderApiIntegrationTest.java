package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class OrderApiIntegrationTest extends IntegrationTest {

    private String admin;
    /** Unique per test so list assertions only see this test's orders. */
    private String phone;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
        phone = "017" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000));
        code = "TS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void createComputesTotalsAndGeneratesOrderNumber() throws Exception {
        long id = createOrder(orderJson("", "900.00", 1, "60", "0"));

        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(startsWith("EZ-")))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.subtotal").value(900.00))
                .andExpect(jsonPath("$.deliveryCharge").value(60.00))
                .andExpect(jsonPath("$.total").value(960.00))
                .andExpect(jsonPath("$.items[0].productCode").value(code))
                .andExpect(jsonPath("$.items[0].lineTotal").value(900.00))
                .andExpect(jsonPath("$.history[0].toStatus").value("PLACED"))
                .andExpect(jsonPath("$.createdBy.name").value("Test ADMIN"))
                .andExpect(jsonPath("$.allowedNextStatuses").value(hasItem("ACCEPTED")));
    }

    @Test
    void customOrderNumberMustBeUnique() throws Exception {
        String number = "MAN-" + UUID.randomUUID().toString().substring(0, 6);
        createOrder(orderJson(number, "100", 2, "0", "0"));

        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(number.toLowerCase(), "100", 1, "0", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NUMBER_TAKEN"));
    }

    @Test
    void discountCannotExceedSubtotal() throws Exception {
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("", "100", 1, "60", "150")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNT_TOO_LARGE"));
    }

    @Test
    void orderWithoutItemsFailsValidation() throws Exception {
        String json = """
                {"paymentMethod":"CASH","deliveryCharge":60,
                 "customer":{"name":"A","phone":"01700000000","address":"Dhaka"},"items":[]}""";
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.items").exists());
    }

    @Test
    void listSearchesByPhoneAndProductCodeAndSplitsActiveFromHistory() throws Exception {
        long first = createOrder(orderJson("", "100", 1, "0", "0"));
        createOrder(orderJson("", "200", 1, "0", "0"));
        changeStatus(first, "{\"status\":\"CANCELLED\"}").andExpect(status().isOk());

        mvc.perform(get("/api/orders").header("Authorization", admin).param("q", phone).param("tab", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].total").value(200.0));

        mvc.perform(get("/api/orders").header("Authorization", admin)
                        .param("q", code.toLowerCase()).param("tab", "history"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("CANCELLED"));

        mvc.perform(get("/api/orders").header("Authorization", admin).param("q", phone).param("size", "1"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void statusFlowEnforcesRulesAndRecordsHistory() throws Exception {
        long id = createOrder(orderJson("", "500", 1, "60", "0"));

        changeStatus(id, "{\"status\":\"RETURNED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        changeStatus(id, "{\"status\":\"SHIPPED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_DETAILS_REQUIRED"));

        changeStatus(id, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"PT123\",\"note\":\"On the way\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courier").value("Pathao"))
                .andExpect(jsonPath("$.allowedNextStatuses").value(hasItem("RETURNED")));

        changeStatus(id, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":30}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnDeliveryCharge").value(30.0))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[1].note").value("On the way"));
    }

    @Test
    void staleVersionIsRejectedOnUpdate() throws Exception {
        long id = createOrder(orderJson("", "100", 1, "0", "0"));
        String update = orderJson("", "150", 1, "0", "0").replace("\"items\"", "\"version\":99,\"items\"");

        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));

        String current = orderJson("", "150", 2, "0", "0").replace("\"items\"", "\"version\":0,\"items\"");
        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(current))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(300.0))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void editingOnlyLineItemsStillBumpsTheVersion() throws Exception {
        long id = createOrder(orderJson("", "100", 1, "0", "0"));
        // Same totals, different description: only the order_items row changes.
        String edit = orderJson("", "100", 1, "0", "0")
                .replace("\"color\":\"White\"", "\"color\":\"Black\"")
                .replace("\"items\"", "\"version\":0,\"items\"");

        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].color").value("Black"))
                .andExpect(jsonPath("$.version").value(1));

        // A second editor still holding version 0 must be rejected.
        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(edit))
                .andExpect(status().isConflict());
    }

    @Test
    void notesAreStoredWithAuthor() throws Exception {
        long id = createOrder(orderJson("", "100", 1, "0", "0"));
        mvc.perform(post("/api/orders/" + id + "/notes").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Customer asked for gift wrap\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].body").value("Customer asked for gift wrap"))
                .andExpect(jsonPath("$.notes[0].author.name").value("Test ADMIN"));
    }

    @Test
    void deletedOrderDisappearsButKeepsItsNumber() throws Exception {
        String number = "DEL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long id = createOrder(orderJson(number, "100", 1, "0", "0"));

        mvc.perform(delete("/api/orders/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(number, "100", 1, "0", "0")))
                .andExpect(status().isConflict());
    }

    @Test
    void permissionsAreEnforcedPerAction() throws Exception {
        String viewer = bearer(Role.MODERATOR, Permission.ORDER_VIEW);
        long id = createOrder(orderJson("", "100", 1, "0", "0"));

        mvc.perform(get("/api/orders/" + id).header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/orders").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("", "100", 1, "0", "0")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/orders/" + id).header("Authorization", viewer)).andExpect(status().isForbidden());
    }

    @Test
    void statsCountOrdersByStage() throws Exception {
        MvcResult before = mvc.perform(get("/api/orders/stats").header("Authorization", admin)).andReturn();
        int pendingBefore = read(before, "$.pending");

        createOrder(orderJson("", "100", 1, "0", "0"));

        MvcResult after = mvc.perform(get("/api/orders/stats").header("Authorization", admin)).andReturn();
        assertThat((int) read(after, "$.pending")).isEqualTo(pendingBefore + 1);
    }

    private long createOrder(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(long id, String json) throws Exception {
        return mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String orderJson(String orderNumber, String unitPrice, int quantity, String deliveryCharge, String discount) {
        return """
                {"orderNumber":"%s","paymentMethod":"CASH","deliveryCharge":%s,"discount":%s,
                 "customer":{"name":"Mehedi Hasan","phone":"%s","email":"m@example.com","address":"Mirpur-2, Dhaka"},
                 "items":[{"productName":"T-Shirt","productCode":"%s","color":"White","size":"L",
                           "unitPrice":%s,"quantity":%d,"imageIds":[]}]}
                """.formatted(orderNumber, deliveryCharge, discount, phone, code, unitPrice, quantity);
    }
}
