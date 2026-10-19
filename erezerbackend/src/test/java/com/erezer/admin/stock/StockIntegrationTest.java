package com.erezer.admin.stock;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class StockIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;
    private String tag;

    @BeforeEach
    void login() throws Exception {
        admin = adminBearer();
        tag = UUID.randomUUID().toString().substring(0, 5);
    }

    @Test
    void addSingleAndBulkVariantsAndSuggestByAnyPartOfTheName() throws Exception {
        long black = variant("Black " + tag, 20);
        mvc.perform(post("/api/stock/variants/bulk").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"Navy Blue %1$s\",\"quantity\":8},{\"name\":\"Maroon %1$s\",\"quantity\":0}]}".formatted(tag)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].low").value(true));
        // Names are unique ignoring case, and a bulk add saves nothing if one name is a problem.
        mvc.perform(post("/api/stock/variants/bulk").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"Olive %1$s\",\"quantity\":5},{\"name\":\"black %1$s\",\"quantity\":1}]}".formatted(tag)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Already in stock: black " + tag));
        mvc.perform(get("/api/stock/variants").param("q", "olive " + tag).header("Authorization", admin))
                .andExpect(jsonPath("$.content.length()").value(0));

        // "a" finds every variant containing it (Black, Navy Blue, Maroon), starting matches first.
        MvcResult suggest = mvc.perform(get("/api/stock/variants/suggest").param("q", "a").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        List<String> names = read(suggest, "$[*].name");
        assertThat(names).allMatch(n -> n.toLowerCase().contains("a"));
        mvc.perform(get("/api/stock/variants/suggest").param("q", "blue " + tag).header("Authorization", admin))
                .andExpect(jsonPath("$[0].name").value("Navy Blue " + tag)).andExpect(jsonPath("$[0].quantity").value(8));

        mvc.perform(get("/api/stock/variants/" + black + "/history").header("Authorization", admin))
                .andExpect(jsonPath("$.content[0].reasonLabel").value("Added"))
                .andExpect(jsonPath("$.content[0].change").value(20));
    }

    @Test
    void ordersTakeStockAndGiveItBackWhenCancelledReturnedOrDeleted() throws Exception {
        long v = variant("Black " + tag, 10);

        long a = order(v, 3);
        assertThat(quantity(v)).isEqualTo(7);
        mvc.perform(get("/api/orders/" + a).header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].color").value("Black " + tag))   // the variant's name is shown
                .andExpect(jsonPath("$.items[0].variantId").value(v));

        // Editing the quantity moves only the difference.
        edit(a, v, 5);
        assertThat(quantity(v)).isEqualTo(5);
        edit(a, v, 1);
        assertThat(quantity(v)).isEqualTo(9);

        setStatus(a, "{\"status\":\"CANCELLED\"}");
        assertThat(quantity(v)).isEqualTo(10);

        long b = order(v, 4);
        setStatus(b, "{\"status\":\"DELIVERED\"}");
        assertThat(quantity(v)).isEqualTo(6);
        setStatus(b, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":50}");
        assertThat(quantity(v)).isEqualTo(10);

        long c = order(v, 2);
        mvc.perform(delete("/api/orders/" + c).header("Authorization", admin)).andExpect(status().isNoContent());
        assertThat(quantity(v)).isEqualTo(10);

        MvcResult history = mvc.perform(get("/api/stock/variants/" + v + "/history").param("size", "50").header("Authorization", admin)).andReturn();
        List<String> reasons = read(history, "$.content[*].reason");
        assertThat(reasons).contains("ORDER_PLACED", "ORDER_CHANGED", "ORDER_CANCELLED", "ORDER_RETURNED", "ORDER_DELETED");
        mvc.perform(get("/api/stock/variants/" + v + "/history").header("Authorization", admin))
                .andExpect(jsonPath("$.content[0].orderNumber").value(startsWith("EZ-")));
    }

    @Test
    void anOrderNeedingMoreThanIsInStockIsRefusedWhole() throws Exception {
        long black = variant("Black " + tag, 2);
        long white = variant("White " + tag, 0);
        String body = orderJson(List.of(item(black, 3), item(white, 1)));
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.detail").value("Not enough stock: Only 2 of Black %1$s left, White %1$s is out of stock.".formatted(tag)));
        assertThat(quantity(black)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from orders where customer_name = :n").param("n", "Stock " + tag).query(Long.class).single()).isZero();

        // Unknown variant ids are refused too.
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(List.of(item(999_999L, 1)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VARIANT_UNKNOWN"));
        // Items without a variant are simply not tracked.
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(List.of(item(null, 5))))).andExpect(status().isCreated());
    }

    @Test
    void singleAndBulkUpdates() throws Exception {
        long a = variant("Olive " + tag, 10);
        long b = variant("Khaki " + tag, 4);
        long c = variant("Grey " + tag, 7);

        mvc.perform(put("/api/stock/variants/" + a).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Olive Green %s\",\"quantity\":15,\"note\":\"New roll arrived\"}".formatted(tag)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Olive Green " + tag)).andExpect(jsonPath("$.quantity").value(15));

        mvc.perform(post("/api/stock/variants/bulk-update").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Stock count\",\"items\":[{\"id\":%d,\"mode\":\"ADD\",\"amount\":5},{\"id\":%d,\"mode\":\"SET\",\"amount\":2},{\"id\":%d,\"mode\":\"REMOVE\",\"amount\":3}]}".formatted(a, b, c)))
                .andExpect(status().isOk());
        assertThat(List.of(quantity(a), quantity(b), quantity(c))).containsExactly(20, 2, 4);

        // Removing more than there is changes nothing at all.
        mvc.perform(post("/api/stock/variants/bulk-update").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":%d,\"mode\":\"ADD\",\"amount\":1},{\"id\":%d,\"mode\":\"REMOVE\",\"amount\":9}]}".formatted(a, b)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"));
        assertThat(List.of(quantity(a), quantity(b))).containsExactly(20, 2);

        mvc.perform(get("/api/stock/variants/" + c + "/history").header("Authorization", admin))
                .andExpect(jsonPath("$.content[0].change").value(-3))
                .andExpect(jsonPath("$.content[0].note").value("Stock count"));
    }

    @Test
    void exchangesTakeTheReplacementAndRestockTheReturnedItemUnlessDefective() throws Exception {
        long black = variant("Black " + tag, 10);
        long white = variant("White " + tag, 10);
        long original = order(black, 2);
        setStatus(original, "{\"status\":\"DELIVERED\"}");
        long itemId = jdbc.sql("select id from order_items where order_id = :id").param("id", original).query(Long.class).single();
        assertThat(quantity(black)).isEqualTo(8);

        long ex = id(mvc.perform(post("/api/orders/" + original + "/exchanges").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeJson(itemId, white, "WRONG_SIZE")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(quantity(white)).isEqualTo(9);       // replacement taken
        assertThat(quantity(black)).isEqualTo(8);       // old item not back until the rider brings it
        setStatus(ex, "{\"status\":\"DELIVERED\"}");
        assertThat(quantity(black)).isEqualTo(9);       // back on the shelf

        long defective = id(mvc.perform(post("/api/orders/" + original + "/exchanges").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(exchangeJson(itemId, white, "DEFECTIVE")))
                .andExpect(status().isCreated()).andReturn());
        setStatus(defective, "{\"status\":\"DELIVERED\"}");
        assertThat(quantity(white)).isEqualTo(8);
        assertThat(quantity(black)).isEqualTo(9);       // a defective piece is not restocked
    }

    @Test
    void ordersFromBeforeStockNeverMoveIt() throws Exception {
        long v = variant("Black " + tag, 5);
        long old = id(mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(List.of(item(null, 1))))).andReturn());
        jdbc.sql("update orders set stock_tracked = false where id = :id").param("id", old).update();
        jdbc.sql("update order_items set variant_id = :v where order_id = :id").param("v", v).param("id", old).update();
        setStatus(old, "{\"status\":\"CANCELLED\"}");
        assertThat(quantity(v)).isEqualTo(5);
    }

    @Test
    void permissions() throws Exception {
        String viewer = bearer(Role.MODERATOR, Permission.STOCK_VIEW);
        mvc.perform(get("/api/stock/variants").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/stock/variants").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"quantity\":1}")).andExpect(status().isForbidden());
        String orderTaker = bearer(Role.MODERATOR, Permission.ORDER_CREATE);
        mvc.perform(get("/api/stock/variants/suggest").param("q", "a").header("Authorization", orderTaker)).andExpect(status().isOk());
        mvc.perform(get("/api/stock/variants").header("Authorization", orderTaker)).andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private long variant(String name, int quantity) throws Exception {
        MvcResult r = mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"quantity\":%d}".formatted(name, quantity))).andExpect(status().isCreated()).andReturn();
        return id(r);
    }

    private int quantity(long variantId) {
        return jdbc.sql("select quantity from stock_variants where id = :id").param("id", variantId).query(Integer.class).single();
    }

    private static String item(Long variantId, int qty) {
        return """
                {"productName":"T-Shirt","productCode":"TS-1","size":"M","unitPrice":900,"quantity":%d,"imageIds":[]%s}
                """.formatted(qty, variantId == null ? "" : ",\"variantId\":" + variantId);
    }

    private String orderJson(List<String> items) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":60,
                 "customer":{"name":"Stock %s","phone":"01711000999","address":"Mirpur-2, Dhaka"},
                 "items":[%s]}
                """.formatted(tag, String.join(",", items));
    }

    private long order(long variantId, int qty) throws Exception {
        return id(mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(List.of(item(variantId, qty))))).andExpect(status().isCreated()).andReturn());
    }

    private void edit(long orderId, long variantId, int qty) throws Exception {
        long version = jdbc.sql("select version from orders where id = :id").param("id", orderId).query(Long.class).single();
        mvc.perform(put("/api/orders/" + orderId).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(List.of(item(variantId, qty))).replace("\"items\"", "\"version\":" + version + ",\"items\"")))
                .andExpect(status().isOk());
    }

    private ResultActions setStatus(long id, String json) throws Exception {
        return mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private static String exchangeJson(long itemId, long replacementVariant, String reason) {
        return """
                {"reason":"%s","deliveryPaidBy":"CUSTOMER","deliveryCharge":60,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":
                   {"productName":"T-Shirt","productCode":"TS-1","size":"L","unitPrice":900,"quantity":1,"imageIds":[],"variantId":%d}}]}
                """.formatted(reason, itemId, replacementVariant);
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) read(result, "$.id")).longValue();
    }
}
