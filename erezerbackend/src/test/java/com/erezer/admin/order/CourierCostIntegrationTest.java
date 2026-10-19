package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The internal courier cost of an order and the dashboard's Total Courier Cost. */
class CourierCostIntegrationTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void login() throws Exception {
        admin = adminBearer();
    }

    @Test
    void costIsAnEstimateUntilTheRealChargeIsRecorded() throws Exception {
        long id = order();
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.courierCost.applicable").value(false));
        // Can be recorded any time, even before shipping.
        record(id, "{\"courierCharge\":65}").andExpect(status().isOk()).andExpect(jsonPath("$.courierCost.total").value(65.0));
        record(id, "{\"courierCharge\":null}").andExpect(status().isOk());

        ship(id);
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.courierCost.applicable").value(true))
                .andExpect(jsonPath("$.courierCost.estimated").value(true))
                .andExpect(jsonPath("$.courierCost.total").value(60.0));     // the delivery charge stands in
        record(id, "{\"courierCharge\":75}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courierCost.estimated").value(false))
                .andExpect(jsonPath("$.courierCost.courierCharge").value(75.0))
                .andExpect(jsonPath("$.courierCost.total").value(75.0))
                .andExpect(jsonPath("$.total").value(1060.0));                // the customer's total is untouched
        record(id, "{\"courierCharge\":null}").andExpect(jsonPath("$.courierCost.total").value(60.0));
        record(id, "{\"courierCharge\":75,\"returnDeliveryCharge\":30}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_RETURNED"));

        // Internal only: never on the invoice.
        byte[] pdf = mvc.perform(get("/api/orders/" + id + "/invoice.pdf").header("Authorization", admin))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1)).doesNotContain("75.00", "Courier cost");

        // Only staff given "Record the courier cost" (not every editor).
        String editor = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_EDIT, Permission.ORDER_STATUS_UPDATE);
        mvc.perform(put("/api/orders/" + id + "/courier-cost").header("Authorization", editor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courierCharge\":75}")).andExpect(status().isForbidden());
        String costKeeper = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_COURIER_COST);
        mvc.perform(put("/api/orders/" + id + "/courier-cost").header("Authorization", costKeeper)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courierCharge\":75}")).andExpect(status().isOk());
    }

    @Test
    void returnsIncludeTheReturnCharge() throws Exception {
        // Refused at the door: delivery + return, or the courier's charge for both ways once known.
        long refused = order();
        ship(refused);
        setStatus(refused, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":50}");
        mvc.perform(get("/api/orders/" + refused).header("Authorization", admin))
                .andExpect(jsonPath("$.courierCost.refused").value(true))
                .andExpect(jsonPath("$.courierCost.total").value(110.0));
        record(refused, "{\"courierCharge\":100}").andExpect(jsonPath("$.courierCost.total").value(100.0));

        // Returned after delivery: the delivery plus the return charge.
        long late = order();
        setStatus(late, "{\"status\":\"DELIVERED\"}");
        setStatus(late, "{\"status\":\"RETURNED\"}");
        record(late, "{\"courierCharge\":55,\"returnDeliveryCharge\":40}")
                .andExpect(jsonPath("$.courierCost.refused").value(false))
                .andExpect(jsonPath("$.courierCost.total").value(95.0));
    }

    @Test
    void dashboardSumsEveryCourierCostOfThePeriod() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Dhaka"));
        MvcResult before = summary(today);

        long delivered = order();
        setStatus(delivered, "{\"status\":\"DELIVERED\"}");
        record(delivered, "{\"courierCharge\":70}");                       // real charge 70
        long estimated = order();
        setStatus(estimated, "{\"status\":\"DELIVERED\"}");                  // unknown: 60 delivery charge
        long refused = order();
        ship(refused);
        setStatus(refused, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":50}"); // 60 + 50

        MvcResult after = summary(today);
        assertThat(number(after, "$.current.courierCost") - number(before, "$.current.courierCost")).isEqualTo(70 + 60 + 110.0);
        assertThat(number(after, "$.current.deliveryCourierCost") - number(before, "$.current.deliveryCourierCost"))
                .isEqualTo(130.0);
    }

    // ---- helpers ----

    private long order() throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethod":"CASH","deliveryCharge":60,
                                 "customer":{"name":"Courier Cost","phone":"01711000777","address":"Mirpur-2, Dhaka"},
                                 "items":[{"productName":"T-Shirt","productCode":"TS-1","unitPrice":1000,"quantity":1,"imageIds":[]}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private void ship(long id) throws Exception {
        setStatus(id, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"PT-1\"}");
    }

    private void setStatus(long id, String json) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private ResultActions record(long id, String json) throws Exception {
        return mvc.perform(put("/api/orders/" + id + "/courier-cost").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private MvcResult summary(LocalDate day) throws Exception {
        return mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)
                .param("from", day.toString()).param("to", day.toString())).andExpect(status().isOk()).andReturn();
    }

    private static double number(MvcResult result, String path) throws Exception {
        return ((Number) read(result, path)).doubleValue();
    }
}
