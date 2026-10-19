package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** "Change status freely", the retired In production step, and the note / courier cost permissions. */
class StatusOverrideIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;

    @BeforeEach
    void login() throws Exception {
        admin = adminBearer();
    }

    @Test
    void onlyStaffWithThePermissionMayMoveAnOrderAnywhere() throws Exception {
        long id = order(null, 1);
        move(admin, id, "DELIVERED").andExpect(status().isOk());

        String normal = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_STATUS_UPDATE);
        mvc.perform(get("/api/orders/" + id).header("Authorization", normal))
                .andExpect(jsonPath("$.allowedNextStatuses").value(org.hamcrest.Matchers.contains("RETURNED")))
                .andExpect(jsonPath("$.freeStatusChange").value(false));
        move(normal, id, "PROCESSING").andExpect(status().isConflict());

        String trusted = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_STATUS_UPDATE, Permission.ORDER_STATUS_OVERRIDE);
        mvc.perform(get("/api/orders/" + id).header("Authorization", trusted))
                .andExpect(jsonPath("$.freeStatusChange").value(true))
                .andExpect(jsonPath("$.allowedNextStatuses", hasItems("PLACED", "ACCEPTED", "PROCESSING", "CANCELLED", "RETURNED")))
                .andExpect(jsonPath("$.allowedNextStatuses", not(hasItem("IN_PRODUCTION"))))
                .andExpect(jsonPath("$.allowedNextStatuses", not(hasItem("DELIVERED"))));

        // Back from Delivered: no longer a sale until delivered again.
        move(trusted, id, "PROCESSING").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.history[-1].note").value(startsWith("Status changed manually")));
        assertThat(deliveredAt(id)).isNull();

        // Cancelled, then reopened: still allowed (never locked), and admins always may.
        move(trusted, id, "CANCELLED").andExpect(status().isOk());
        move(admin, id, "ACCEPTED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        move(admin, id, "ACCEPTED").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SAME_STATUS"));
        move(admin, id, "IN_PRODUCTION").andExpect(status().isBadRequest());
    }

    @Test
    void aMistakenReturnCanBeUndoneAndKeepsTheDeliveryDate() throws Exception {
        long id = order(null, 1);
        move(admin, id, "DELIVERED").andExpect(status().isOk());
        Object delivered = deliveredAt(id);
        move(admin, id, "RETURNED").andExpect(status().isOk());
        move(admin, id, "DELIVERED").andExpect(status().isOk());
        assertThat(deliveredAt(id)).isEqualTo(delivered);
        assertThat(jdbc.sql("select returned_at from orders where id = :id").param("id", id).query(java.sql.Timestamp.class).list().get(0)).isNull();
    }

    @Test
    void reopeningACancelledOrderTakesTheStockAgain() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 5);
        MvcResult v = mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Black " + tag + "\",\"quantity\":3}")).andExpect(status().isCreated()).andReturn();
        long variant = ((Number) read(v, "$.id")).longValue();
        long id = order(variant, 2);
        move(admin, id, "CANCELLED").andExpect(status().isOk());
        assertThat(stock(variant)).isEqualTo(3);
        move(admin, id, "PROCESSING").andExpect(status().isOk());
        assertThat(stock(variant)).isEqualTo(1);

        // Not enough left: the change is refused.
        long other = order(variant, 1);
        move(admin, other, "CANCELLED").andExpect(status().isOk());
        move(admin, id, "CANCELLED").andExpect(status().isOk());       // stock back to 3
        long big = order(variant, 3);                                    // takes all 3
        move(admin, other, "ACCEPTED").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
        assertThat(big).isPositive();
    }

    @Test
    void notesHaveTheirOwnPermissionAndWorkOnAnyStatus() throws Exception {
        long id = order(null, 1);
        move(admin, id, "CANCELLED").andExpect(status().isOk());
        String editor = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_EDIT, Permission.ORDER_STATUS_UPDATE);
        note(editor, id).andExpect(status().isForbidden());
        String noter = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_NOTE_ADD);
        note(noter, id).andExpect(status().isOk()).andExpect(jsonPath("$.notes[0].body").value("Customer called"));
    }

    // ---- helpers ----

    private long order(Long variantId, int qty) throws Exception {
        MvcResult r = mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethod":"CASH","deliveryCharge":60,
                                 "customer":{"name":"Override Test","phone":"01711000444","address":"Mirpur-2, Dhaka"},
                                 "items":[{"productName":"T-Shirt","unitPrice":900,"quantity":%d,"imageIds":[]%s}]}
                                """.formatted(qty, variantId == null ? "" : ",\"variantId\":" + variantId)))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(r, "$.id")).longValue();
    }

    private ResultActions move(String bearer, long id, String target) throws Exception {
        return mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"}"));
    }

    private ResultActions note(String bearer, long id) throws Exception {
        return mvc.perform(post("/api/orders/" + id + "/notes").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Customer called\"}"));
    }

    private Object deliveredAt(long id) {
        return jdbc.sql("select delivered_at from orders where id = :id").param("id", id).query(java.sql.Timestamp.class).list().get(0);
    }

    private int stock(long variant) {
        return jdbc.sql("select quantity from stock_variants where id = :id").param("id", variant).query(Integer.class).single();
    }
}
