package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Optional advance paid before delivery; the due is total − advance. */
class AdvanceDueIntegrationTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void login() throws Exception {
        admin = adminBearer();
    }

    @Test
    void dueIsTotalMinusAdvanceAndCourierCollectsOnlyTheDue() throws Exception {
        long id = create(1000, 60, 300);
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.total").value(1060.0))
                .andExpect(jsonPath("$.advance").value(300.0))
                .andExpect(jsonPath("$.due").value(760.0));

        // The rider collects only what is still due.
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/courier/preview").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[" + id + "]}"))
                .andExpect(jsonPath("$.parcels[0].codAmount").value(760.0));

        // No advance: everything is due (and older orders behave exactly as before).
        long noAdvance = create(500, 60, null);
        mvc.perform(get("/api/orders/" + noAdvance).header("Authorization", admin))
                .andExpect(jsonPath("$.advance").value(0.0))
                .andExpect(jsonPath("$.due").value(560.0));
    }

    @Test
    void advanceCannotExceedTheTotal() throws Exception {
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(1000, 60, 1061)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADVANCE_TOO_LARGE"));

        long id = create(1000, 60, 800);
        long version = ((Number) read(mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andReturn(), "$.version")).longValue();
        // Lowering the price below the advance is refused too.
        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(500, 60, 800).replace("\"items\"", "\"version\":" + version + ",\"items\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADVANCE_TOO_LARGE"));
    }

    @Test
    void filterByAdvancePaidOrDue() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        long partly = create(1000, 60, 300, tag);
        long fullyPaid = create(1000, 60, 1060, tag);
        long nothingPaid = create(1000, 60, null, tag);

        assertThat(ids("ADVANCE", tag)).containsExactlyInAnyOrder(partly, fullyPaid);
        assertThat(ids("DUE", tag)).containsExactlyInAnyOrder(partly, nothingPaid);
    }

    // ---- helpers ----

    private List<Long> ids(String balance, String tag) throws Exception {
        MvcResult result = mvc.perform(get("/api/orders").header("Authorization", admin)
                .param("tab", "all").param("balance", balance).param("q", tag)).andExpect(status().isOk()).andReturn();
        List<Number> ids = read(result, "$.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private long create(int price, int delivery, Integer advance) throws Exception {
        return create(price, delivery, advance, "x");
    }

    private long create(int price, int delivery, Integer advance, String tag) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(price, delivery, advance).replace("Advance Test", "Advance Test " + tag)))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private static String json(int price, int delivery, Integer advance) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":%d,%s
                 "customer":{"name":"Advance Test","phone":"01711000888","address":"Mirpur-2, Dhaka"},
                 "items":[{"productName":"T-Shirt","productCode":"TS-1","unitPrice":%d,"quantity":1,"imageIds":[]}]}
                """.formatted(delivery, advance == null ? "" : "\"advance\":" + advance + ",", price);
    }
}
