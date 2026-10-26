package com.erezer.admin.order;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class OrderDateIntegrationTest extends IntegrationTest {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
    }

    @Test
    void aPermittedUserChoosesTheOrderDateWhenAddingAndEditing() throws Exception {
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long id = id(create(admin, threeDaysAgo).andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.createdAt").value(threeDaysAgo.toString()))
                .andExpect(jsonPath("$.history[0].toStatus").value("PLACED"))
                .andExpect(jsonPath("$.history[0].changedAt").value(threeDaysAgo.toString()));

        Instant lastWeek = threeDaysAgo.minus(4, ChronoUnit.DAYS);
        edit(admin, id, lastWeek).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(lastWeek.toString()))
                .andExpect(jsonPath("$.notes[0].body").value(startsWith("Order date changed from")));
    }

    @Test
    void withoutThePermissionTheDateCannotBeChosen() throws Exception {
        String clerk = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_CREATE, Permission.ORDER_EDIT);
        create(clerk, Instant.now().minus(1, ChronoUnit.DAYS)).andExpect(status().isForbidden());
        long id = id(create(clerk, null).andExpect(status().isCreated()).andReturn());

        // Sending the order's own date back (an untouched form) is fine; moving it is not.
        String createdAt = read(mvc.perform(get("/api/orders/" + id).header("Authorization", clerk)).andReturn(), "$.createdAt");
        edit(clerk, id, Instant.parse(createdAt)).andExpect(status().isOk());
        edit(clerk, id, Instant.parse(createdAt).minus(2, ChronoUnit.DAYS)).andExpect(status().isForbidden());
    }

    @Test
    void aFutureDateIsAllowedButNotAMistypedYear() throws Exception {
        Instant inTwoDays = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long id = id(create(admin, inTwoDays).andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.createdAt").value(inTwoDays.toString()));
        create(admin, Instant.parse("1999-01-01T00:00:00Z")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE"));
    }

    @Test
    void theDeliveredDateCanBeSetByHand() throws Exception {
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long id = id(create(admin, fiveDaysAgo).andExpect(status().isCreated()).andReturn());
        delivered(admin, id, fiveDaysAgo.plus(1, ChronoUnit.DAYS)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_DELIVERED"));

        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERED\"}")).andExpect(status().isOk());
        Instant twoDaysAgo = fiveDaysAgo.plus(3, ChronoUnit.DAYS);
        delivered(admin, id, twoDaysAgo).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredAt").value(twoDaysAgo.toString()))
                .andExpect(jsonPath("$.history[?(@.toStatus == 'DELIVERED')].changedAt").value(twoDaysAgo.toString()))
                .andExpect(jsonPath("$.notes[0].body").value(startsWith("Delivered date changed from")));

        delivered(admin, id, fiveDaysAgo.minus(1, ChronoUnit.DAYS)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERED_BEFORE_ORDER"));
        String clerk = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_EDIT);
        delivered(clerk, id, twoDaysAgo.plus(1, ChronoUnit.HOURS)).andExpect(status().isForbidden());
    }

    @Test
    void ordersCanBeMovedToADayTogether() throws Exception {
        java.time.ZoneId dhaka = java.time.ZoneId.of("Asia/Dhaka");
        Instant morning = java.time.LocalDate.now(dhaka).minusDays(1).atTime(9, 15).atZone(dhaka).toInstant();
        Instant evening = java.time.LocalDate.now(dhaka).minusDays(2).atTime(20, 40).atZone(dhaka).toInstant();
        long a = id(create(admin, morning).andExpect(status().isCreated()).andReturn());
        long b = id(create(admin, evening).andExpect(status().isCreated()).andReturn());
        java.time.LocalDate day = java.time.LocalDate.now(dhaka).minusDays(10);

        // Each keeps its own time of day.
        bulk("[%d,%d]".formatted(a, b), "ORDER", day, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(2));
        mvc.perform(get("/api/orders/" + a).header("Authorization", admin))
                .andExpect(jsonPath("$.createdAt").value(day.atTime(9, 15).atZone(dhaka).toInstant().toString()));
        mvc.perform(get("/api/orders/" + b).header("Authorization", admin))
                .andExpect(jsonPath("$.createdAt").value(day.atTime(20, 40).atZone(dhaka).toInstant().toString()));

        // Delivered dates: an order that isn't delivered is skipped and listed.
        mvc.perform(post("/api/orders/" + a + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERED\"}")).andExpect(status().isOk());
        bulk("[%d,%d]".formatted(a, b), "DELIVERED", day.plusDays(1), "15:00")
                .andExpect(jsonPath("$.changed").value(1))
                .andExpect(jsonPath("$.skipped[0]").value(org.hamcrest.Matchers.endsWith(": not delivered yet")));
        mvc.perform(get("/api/orders/" + a).header("Authorization", admin))
                .andExpect(jsonPath("$.deliveredAt").value(day.plusDays(1).atTime(15, 0).atZone(dhaka).toInstant().toString()));

        String clerk = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_EDIT);
        mvc.perform(post("/api/orders/bulk-date").header("Authorization", clerk).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[%d],\"field\":\"ORDER\",\"date\":\"%s\"}".formatted(a, day))).andExpect(status().isForbidden());
    }

    private ResultActions bulk(String ids, String field, java.time.LocalDate date, String time) throws Exception {
        return mvc.perform(post("/api/orders/bulk-date").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":%s,\"field\":\"%s\",\"date\":\"%s\"%s}".formatted(ids, field, date,
                        time == null ? "" : ",\"time\":\"" + time + "\"")));
    }

    private ResultActions delivered(String bearer, long id, Instant when) throws Exception {
        return mvc.perform(put("/api/orders/" + id + "/delivered-date").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveredAt\":\"" + when + "\"}"));
    }

    private ResultActions create(String bearer, Instant orderedAt) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json(orderedAt, null)));
    }

    private ResultActions edit(String bearer, long id, Instant orderedAt) throws Exception {
        long version = ((Number) read(mvc.perform(get("/api/orders/" + id).header("Authorization", bearer)).andReturn(), "$.version")).longValue();
        return mvc.perform(put("/api/orders/" + id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json(orderedAt, version)));
    }

    private static String json(Instant orderedAt, Long version) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":60,
                 "customer":{"name":"Date Test","phone":"01711000555","address":"Mirpur-2, Dhaka"},
                 "items":[{"productName":"T-Shirt","unitPrice":900,"quantity":1,"imageIds":[]}]%s%s}
                """.formatted(orderedAt == null ? "" : ",\"orderedAt\":\"" + orderedAt + "\"",
                version == null ? "" : ",\"version\":" + version);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) read(result, "$.id")).longValue();
    }
}
