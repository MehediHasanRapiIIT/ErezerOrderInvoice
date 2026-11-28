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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PrintQueueIntegrationTest extends IntegrationTest {

    private String admin;
    private String tag;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
        tag = "Print " + ThreadLocalRandom.current().ints(8, 'a', 'z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
    }

    @Test
    void ordersWaitUntilShippedAndShowOnlyTheProducts() throws Exception {
        long first = create();
        long second = create();
        String printer = bearer(Role.MODERATOR, Permission.PRINT_QUEUE_VIEW);

        queue(printer, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.content[0].items[0].productName").value("Printed Tee"))
                .andExpect(jsonPath("$.content[0].items[0].size").value("XL"))
                .andExpect(jsonPath("$.content[0].items[0].color").value("Black"))
                .andExpect(jsonPath("$.content[0].items[0].quantity").value(2))
                // No money and no customer for people who only print.
                .andExpect(jsonPath("$.content[0].total").doesNotExist())
                .andExpect(jsonPath("$.content[0].items[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerName").doesNotExist());
        queue(printer, null, "newest").andExpect(jsonPath("$.content[0].id").value(second));

        // Shipped or cancelled orders leave the queue.
        moveTo(first, "SHIPPED");
        moveTo(second, "CANCELLED");
        assertThat(ids(queue(printer, null, null))).isEmpty();
    }

    @Test
    void markingPrintedNeedsItsOwnPermissionAndKeepsTheOrderUntilShipped() throws Exception {
        long id = create();
        String viewer = bearer(Role.MODERATOR, Permission.PRINT_QUEUE_VIEW);
        String marker = bearer(Role.MODERATOR, Permission.PRINT_QUEUE_MARK);

        mark(viewer, id, true).andExpect(status().isForbidden());
        mark(marker, id, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.printedAt").exists())
                .andExpect(jsonPath("$.printedBy").exists());

        assertThat(ids(queue(viewer, true, null))).containsExactly(id);
        assertThat(ids(queue(viewer, false, null))).isEmpty();
        mvc.perform(get("/api/print-queue/counts").param("q", tag).header("Authorization", viewer))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.printed").value(1))
                .andExpect(jsonPath("$.notPrinted").value(0));

        mark(marker, id, false).andExpect(status().isOk()).andExpect(jsonPath("$.printedAt").doesNotExist());
        assertThat(ids(queue(viewer, false, null))).containsExactly(id);

        moveTo(id, "SHIPPED");
        mark(marker, id, true).andExpect(status().isConflict());
    }

    @Test
    void withoutThePermissionTheQueueIsClosed() throws Exception {
        String clerk = bearer(Role.MODERATOR, Permission.ORDER_VIEW);
        mvc.perform(get("/api/print-queue").header("Authorization", clerk)).andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private long create() throws Exception {
        String json = """
                {"paymentMethod":"CASH","deliveryCharge":60,
                 "customer":{"name":"%s","phone":"01711000555","address":"Mirpur-2, Dhaka"},
                 "items":[{"productName":"Printed Tee","color":"Black","size":"XL","unitPrice":900,"quantity":2,"imageIds":[]}]}
                """.formatted(tag);
        return ((Number) read(mvc.perform(post("/api/orders").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()).andReturn(), "$.id")).longValue();
    }

    private ResultActions queue(String bearer, Boolean printed, String sort) throws Exception {
        var request = get("/api/print-queue").param("q", tag).header("Authorization", bearer);
        if (printed != null) {
            request = request.param("printed", printed.toString());
        }
        if (sort != null) {
            request = request.param("sort", sort);
        }
        return mvc.perform(request);
    }

    private static List<Long> ids(ResultActions result) throws Exception {
        List<Number> ids = read(result.andReturn(), "$.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private ResultActions mark(String bearer, long id, boolean printed) throws Exception {
        return mvc.perform(put("/api/print-queue/" + id + "/printed").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"printed\":" + printed + "}"));
    }

    private void moveTo(long id, String status) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\""
                        + ("SHIPPED".equals(status) ? ",\"courier\":\"Pathao\",\"trackingNumber\":\"T\"" : "") + "}"))
                .andExpect(status().isOk());
    }
}
