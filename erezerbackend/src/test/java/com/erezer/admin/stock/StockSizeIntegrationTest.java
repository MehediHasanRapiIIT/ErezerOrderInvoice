package com.erezer.admin.stock;

import static org.assertj.core.api.Assertions.assertThat;
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

class StockSizeIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;
    private String tag;
    private long s;
    private long m;
    private long l;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
        tag = UUID.randomUUID().toString().substring(0, 5);
        s = sizeId("S");
        m = sizeId("M");
        l = sizeId("L");
    }

    @Test
    void onlyAdminsManageTheSizeList() throws Exception {
        String stockManager = bearer(Role.MODERATOR, Permission.STOCK_VIEW, Permission.STOCK_MANAGE);
        mvc.perform(get("/api/stock/sizes").header("Authorization", stockManager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'No size')]").isEmpty());
        mvc.perform(post("/api/stock/sizes").header("Authorization", stockManager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X" + tag + "\"}")).andExpect(status().isForbidden());

        String name = "3X" + tag;
        mvc.perform(post("/api/stock/sizes").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[-1:].name").value(name)); // added at the end
        mvc.perform(post("/api/stock/sizes").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name.toLowerCase() + "\"}")).andExpect(status().isConflict());
        long id = sizeId(name);

        // Reorder: the new size first.
        List<Long> ids = jdbc.sql("select id from sizes where not legacy order by position, id").query(Long.class).list();
        List<Long> order = new java.util.ArrayList<>(ids);
        order.remove(id);
        order.addFirst(id);
        mvc.perform(put("/api/stock/sizes/order").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":" + order + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value(name));
        mvc.perform(put("/api/stock/sizes/order").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + id + "]}")).andExpect(status().isBadRequest());

        // A used size can't be deleted, only switched off; a switched-off size can't be added to variants.
        createVariant("Grey " + tag, "[{\"sizeId\":" + id + ",\"quantity\":1}]");
        mvc.perform(delete("/api/stock/sizes/" + id).header("Authorization", admin)).andExpect(status().isConflict());
        mvc.perform(put("/api/stock/sizes/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"active\":false}")).andExpect(status().isOk());
        mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pink %s\",\"sizes\":[{\"sizeId\":%d,\"quantity\":1}]}".formatted(tag, id)))
                .andExpect(status().isBadRequest());

        String unused = "U" + tag;
        mvc.perform(post("/api/stock/sizes").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + unused + "\"}")).andExpect(status().isCreated());
        mvc.perform(delete("/api/stock/sizes/" + sizeId(unused)).header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void ordersTakeStockFromTheChosenSize() throws Exception {
        long black = createVariant("Black " + tag, "[{\"sizeId\":%d,\"quantity\":4},{\"sizeId\":%d,\"quantity\":6}]".formatted(m, s));
        mvc.perform(get("/api/stock/variants/suggest").param("q", "black " + tag).header("Authorization", admin))
                .andExpect(jsonPath("$[0].quantity").value(10))
                .andExpect(jsonPath("$[0].sizes[0].size").value("S"))   // in the size list's order
                .andExpect(jsonPath("$[0].sizes[1].size").value("M"));
        long blackM = item(black, m);
        long blackS = item(black, s);

        long order = order(black, blackM, 3);
        assertThat(qty(blackM)).isEqualTo(1);
        assertThat(qty(blackS)).isEqualTo(6);
        assertThat(total(black)).isEqualTo(7);
        assertThat(jdbc.sql("select size from order_items where order_id = :id").param("id", order).query(String.class).single())
                .isEqualTo("M"); // the size text follows the chosen size

        // Several sizes: the size must be chosen.
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(itemJson(black, null, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SIZE_REQUIRED"));
        mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(itemJson(black, blackM, 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough stock: Only 1 of Black %s / M left.".formatted(tag)));

        setStatus(order, "{\"status\":\"CANCELLED\"}");
        assertThat(qty(blackM)).isEqualTo(4);
    }

    @Test
    void editingSizesAddsRemovesAndKeepsHistory() throws Exception {
        long navy = createVariant("Navy " + tag, "[{\"sizeId\":%d,\"quantity\":4}]".formatted(s));
        long version = version(navy);
        // S still has pieces: it can't be taken off.
        update(navy, "[{\"sizeId\":%d,\"quantity\":3}]".formatted(m), version).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SIZE_NOT_EMPTY"));
        update(navy, "[{\"sizeId\":%d,\"quantity\":0},{\"sizeId\":%d,\"quantity\":3}]".formatted(s, m), version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.version").value(version + 1));
        update(navy, "[{\"sizeId\":%d,\"quantity\":3},{\"sizeId\":%d,\"quantity\":2}]".formatted(m, l), version + 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizes.length()").value(2))
                .andExpect(jsonPath("$.sizes[0].size").value("M"))
                .andExpect(jsonPath("$.sizes[1].size").value("L"))
                .andExpect(jsonPath("$.quantity").value(5));

        mvc.perform(post("/api/stock/variants/bulk-update").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"stockItemId\":%d,\"mode\":\"ADD\",\"amount\":10}]}".formatted(item(navy, l))))
                .andExpect(status().isOk());
        assertThat(qty(item(navy, l))).isEqualTo(12);
        mvc.perform(get("/api/stock/variants/" + navy + "/history").header("Authorization", admin))
                .andExpect(jsonPath("$.content[0].size").value("L"))
                .andExpect(jsonPath("$.content[0].change").value(10));
    }

    @Test
    void stockCountedBeforeSizesIsSpreadIntoSizes() throws Exception {
        // A variant without sizes holds its pieces in "No size" (as all stock did before this update).
        long olive = id(mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Olive %s\",\"quantity\":9}".formatted(tag))).andExpect(status().isCreated()).andReturn());
        long noSize = jdbc.sql("select id from sizes where legacy").query(Long.class).single();
        mvc.perform(get("/api/stock/variants").param("q", "olive " + tag).header("Authorization", admin))
                .andExpect(jsonPath("$.content[0].sizes[0].size").value("No size"))
                .andExpect(jsonPath("$.content[0].sizes[0].legacy").value(true));

        update(olive, "[{\"sizeId\":%d,\"quantity\":0},{\"sizeId\":%d,\"quantity\":4},{\"sizeId\":%d,\"quantity\":5}]".formatted(noSize, s, m),
                version(olive))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizes.length()").value(2)) // an empty "No size" is hidden
                .andExpect(jsonPath("$.quantity").value(9));
    }

    @Test
    void mergingMovesPiecesAndTheOrdersHoldingThem() throws Exception {
        long old = id(mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Maroon - M %s\",\"quantity\":5}".formatted(tag))).andExpect(status().isCreated()).andReturn());
        long order = order(old, null, 2); // holds 2 of the old variant
        long maroon = createVariant("Maroon " + tag, "[{\"sizeId\":%d,\"quantity\":1}]".formatted(s));

        mvc.perform(post("/api/stock/variants/" + old + "/merge").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVariantId\":%d,\"sizeId\":%d}".formatted(maroon, m)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(4));
        long maroonM = item(maroon, m);
        assertThat(qty(maroonM)).isEqualTo(3);
        assertThat(jdbc.sql("select stock_item_id from order_items where order_id = :id").param("id", order).query(Long.class).single())
                .isEqualTo(maroonM);
        mvc.perform(get("/api/stock/variants").param("q", "maroon - m " + tag).header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Cancelling the order gives its pieces back to the new size.
        setStatus(order, "{\"status\":\"CANCELLED\"}");
        assertThat(qty(maroonM)).isEqualTo(5);
    }

    @Test
    void anExchangeForAnotherSizeSwapsTheSizes() throws Exception {
        long white = createVariant("White " + tag, "[{\"sizeId\":%d,\"quantity\":5},{\"sizeId\":%d,\"quantity\":5}]".formatted(m, l));
        long whiteM = item(white, m);
        long whiteL = item(white, l);
        long original = order(white, whiteM, 1);
        setStatus(original, "{\"status\":\"DELIVERED\"}");
        long itemId = jdbc.sql("select id from order_items where order_id = :id").param("id", original).query(Long.class).single();

        long ex = id(mvc.perform(post("/api/orders/" + original + "/exchanges").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":60,"refund":false,
                                 "items":[{"originalItemId":%d,"quantity":1,"replacement":
                                   {"productName":"T-Shirt","unitPrice":900,"quantity":1,"imageIds":[],"variantId":%d,"stockItemId":%d}}]}
                                """.formatted(itemId, white, whiteL)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(qty(whiteL)).isEqualTo(4);
        setStatus(ex, "{\"status\":\"DELIVERED\"}");
        assertThat(qty(whiteM)).isEqualTo(5); // the M came back with the rider
    }

    // ---- helpers ----

    private long sizeId(String name) {
        return jdbc.sql("select id from sizes where name = :n").param("n", name).query(Long.class).single();
    }

    private long createVariant(String name, String sizesJson) throws Exception {
        return id(mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"sizes\":%s}".formatted(name, sizesJson))).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions update(long variant, String sizesJson, long version) throws Exception {
        String name = jdbc.sql("select name from stock_variants where id = :id").param("id", variant).query(String.class).single();
        return mvc.perform(put("/api/stock/variants/" + variant).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"sizes\":%s,\"version\":%d}".formatted(name, sizesJson, version)));
    }

    private long version(long variant) {
        return jdbc.sql("select version from stock_variants where id = :id").param("id", variant).query(Long.class).single();
    }

    private long item(long variant, long size) {
        return jdbc.sql("select id from stock_items where variant_id = :v and size_id = :s").param("v", variant).param("s", size)
                .query(Long.class).single();
    }

    private int qty(long stockItem) {
        return jdbc.sql("select quantity from stock_items where id = :id").param("id", stockItem).query(Integer.class).single();
    }

    private int total(long variant) {
        return jdbc.sql("select quantity from stock_variants where id = :id").param("id", variant).query(Integer.class).single();
    }

    private static String itemJson(long variant, Long stockItem, int qty) {
        return """
                {"productName":"T-Shirt","size":"XL","unitPrice":900,"quantity":%d,"imageIds":[],"variantId":%d%s}
                """.formatted(qty, variant, stockItem == null ? "" : ",\"stockItemId\":" + stockItem);
    }

    private String orderJson(String item) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":60,
                 "customer":{"name":"Size %s","phone":"01711000998","address":"Mirpur-2, Dhaka"},
                 "items":[%s]}
                """.formatted(tag, item);
    }

    private long order(long variant, Long stockItem, int qty) throws Exception {
        return id(mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(itemJson(variant, stockItem, qty)))).andExpect(status().isCreated()).andReturn());
    }

    private void setStatus(long id, String json) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) read(result, "$.id")).longValue();
    }
}
