package com.erezer.admin.order;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class ReturnIntegrationTest extends IntegrationTest {

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
    void partReturnCustomerPaysTheChargeOutOfTheRefundThenTheRestMakesTheOrderReturned() throws Exception {
        long black = variant("Black", 10);
        long id = deliveredOrder(0, 0, item("T-Shirt " + tag, 1000, 2, black), item("Cap " + tag, 400, 1, null));
        assertThat(stock(black)).isEqualTo(8);
        List<Long> items = itemIds(id);
        MvcResult before = summary();

        // 1 of 2 tees back, didn't like it, customer pays the ৳120 charge → refund 1,000 − 120 = 880.
        doReturn(id, items.get(0), 1, "DID_NOT_LIKE", "CUSTOMER", 120).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.partlyReturned").value(true))
                .andExpect(jsonPath("$.returns[0].kind").value("AFTER_DELIVERY"))
                .andExpect(jsonPath("$.returns[0].itemsValue").value(1000.0))
                .andExpect(jsonPath("$.returns[0].refundAmount").value(880.0))
                .andExpect(jsonPath("$.returns[0].restock").value(false))
                .andExpect(jsonPath("$.notes[0].body").value(startsWith("Return: T-Shirt")));
        assertThat(stock(black)).isEqualTo(8);                                        // returns don't change stock

        MvcResult after = summary();
        assertThat(delta(before, after, "$.current.refunds")).isEqualTo(880.0);
        assertThat(delta(before, after, "$.current.returnDeliveryTotal")).isEqualTo(120.0);
        // Erezer is out exactly the tee's price: refunded 880 and paid the courier 120.
        assertThat(delta(before, after, "$.current.netProfit")).isEqualTo(-1000.0);
        assertThat(delta(before, after, "$.returns.itemsPaidByCustomer")).isEqualTo(1.0);
        assertThat(delta(before, after, "$.returns.chargesPaidByCustomer")).isEqualTo(120.0);

        // Only what is still with the customer can be returned.
        mvc.perform(get("/api/orders/" + id + "/return-options").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].remaining").value(1))
                .andExpect(jsonPath("$.items[0].alreadyReturned").value(1));
        doReturn(id, items.get(0), 2, "DID_NOT_LIKE", "CUSTOMER", 0)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Only 1 of T-Shirt " + tag + " can still be returned"));

        // The rest comes back defective, Erezer pays: full refund; the order is now Returned.
        doReturn(id, List.of(new long[] {items.get(0), 1}, new long[] {items.get(1), 1}), "DEFECTIVE", "EREZER", 100)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returns[0].refundAmount").value(1400.0))
                .andExpect(jsonPath("$.returns[0].restock").value(false));
        assertThat(stock(black)).isEqualTo(8);                                        // still no stock change
        mvc.perform(get("/api/orders/" + id + "/return-options").header("Authorization", admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_RETURNABLE"));
    }

    @Test
    void refusedAtTheDoorTheAdminChoosesWhoPays() throws Exception {
        // ৳300 advance; refused; customer covers delivery 60 + return 50 → refund 300 − 110 = 190.
        long id = order(300, item("Hoodie " + tag, 1500, 1, null));
        move(id, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"PT-9\"}");
        MvcResult before = summary();
        move(id, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":50,\"returnPaidBy\":\"CUSTOMER\"}");
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.returns[0].kind").value("REFUSED"))
                .andExpect(jsonPath("$.returns[0].reasonLabel").value("Refused at the door"))
                .andExpect(jsonPath("$.returns[0].paidBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.returns[0].refundAmount").value(190.0));
        MvcResult after = summary();
        assertThat(delta(before, after, "$.current.returnDeliveryTotal")).isEqualTo(110.0);
        assertThat(delta(before, after, "$.current.returnChargesRecovered")).isEqualTo(110.0);
        assertThat(delta(before, after, "$.current.netProfit")).isEqualTo(0.0);       // the customer covered it
        assertThat(delta(before, after, "$.returns.itemsPaidByCustomer")).isEqualTo(1.0);

        // Erezer pays: the same parcel costs Erezer 110.
        long other = order(0, item("Hoodie " + tag, 1500, 1, null));
        move(other, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"PT-10\"}");
        MvcResult b2 = summary();
        move(other, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":50,\"returnPaidBy\":\"EREZER\"}");
        assertThat(delta(b2, summary(), "$.current.netProfit")).isEqualTo(-110.0);
    }

    @Test
    void aReturnCanBeCorrectedAndMovingTheOrderOutOfReturnedCancelsIt() throws Exception {
        long id = order(0, item("Polo " + tag, 900, 1, null));
        move(id, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"PT-11\"}");
        move(id, "{\"status\":\"RETURNED\"}");                                       // e.g. reported by the courier
        MvcResult order = mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.returns[0].paidBy").value("EREZER")).andReturn();
        long returnId = ((Number) read(order, "$.returns[0].id")).longValue();

        mvc.perform(put("/api/returns/" + returnId).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"REFUSED\",\"paidBy\":\"CUSTOMER\",\"returnCharge\":40,\"note\":\"Customer will pay by bKash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returns[0].paidBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.returns[0].returnCharge").value(40.0))
                .andExpect(jsonPath("$.returnDeliveryCharge").value(40.0));

        MvcResult before = summary();
        move(id, "{\"status\":\"SHIPPED\"}");                                         // admin: free change, undoes the return
        mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andExpect(jsonPath("$.returns[0].active").value(false));
        assertThat(delta(before, summary(), "$.returns.returns")).isEqualTo(-1.0);
    }

    @Test
    void exchangedPiecesCannotBeReturnedAndReturnedOnesCannotBeExchanged() throws Exception {
        long id = deliveredOrder(0, 0, item("Shirt " + tag, 800, 2, null));
        long itemId = itemIds(id).getFirst();
        mvc.perform(post("/api/orders/" + id + "/exchanges").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":60,
                                 "items":[{"originalItemId":%d,"quantity":1,"replacement":{"productName":"Shirt","unitPrice":800,"quantity":1,"imageIds":[]}}]}
                                """.formatted(itemId)))
                .andExpect(status().isCreated());
        doReturn(id, itemId, 1, "WRONG_SIZE", "CUSTOMER", 0).andExpect(status().isCreated());
        // Both pieces are gone now: one exchanged, one returned.
        doReturn(id, itemId, 1, "WRONG_SIZE", "CUSTOMER", 0).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/" + id + "/exchanges").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":60,
                                 "items":[{"originalItemId":%d,"quantity":1,"replacement":{"productName":"Shirt","unitPrice":800,"quantity":1,"imageIds":[]}}]}
                                """.formatted(itemId)))
                .andExpect(status().isConflict());
    }

    @Test
    void soldProductsLeaveOutReturnedPiecesAndPermissions() throws Exception {
        String name = "Jersey " + tag;
        long id = deliveredOrder(0, 0, item(name, 700, 3, null));
        doReturn(id, itemIds(id).getFirst(), 1, "WRONG_SIZE", "CUSTOMER", 0).andExpect(status().isCreated());
        mvc.perform(get("/api/sold-products/summary").param("category", name).header("Authorization", admin))
                .andExpect(jsonPath("$.totals.pieces").value(2))
                .andExpect(jsonPath("$.totals.sale").value(1400.0));

        String noPermission = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_STATUS_UPDATE);
        mvc.perform(get("/api/orders/" + id + "/return-options").header("Authorization", noPermission)).andExpect(status().isForbidden());
        String returner = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.RETURN_CREATE);
        mvc.perform(get("/api/orders/" + id + "/return-options").header("Authorization", returner)).andExpect(status().isOk());
    }

    // ---- helpers ----

    private ResultActions doReturn(long orderId, long itemId, int qty, String reason, String paidBy, int charge) throws Exception {
        return doReturn(orderId, List.of(new long[] {itemId, qty}), reason, paidBy, charge);
    }

    private ResultActions doReturn(long orderId, List<long[]> lines, String reason, String paidBy, int charge) throws Exception {
        String items = String.join(",", lines.stream().map(l -> "{\"orderItemId\":" + l[0] + ",\"quantity\":" + l[1] + "}").toList());
        return mvc.perform(post("/api/orders/" + orderId + "/returns").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"%s\",\"paidBy\":\"%s\",\"returnCharge\":%d,\"items\":[%s]}".formatted(reason, paidBy, charge, items)));
    }

    private static String item(String name, int price, int qty, Long variantId) {
        return "{\"productName\":\"%s\",\"unitPrice\":%d,\"quantity\":%d,\"imageIds\":[]%s}"
                .formatted(name, price, qty, variantId == null ? "" : ",\"variantId\":" + variantId);
    }

    private long order(int advance, String... items) throws Exception {
        MvcResult r = mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethod":"CASH","deliveryCharge":60,"advance":%d,
                                 "customer":{"name":"Return Test","phone":"01711000333","address":"Mirpur-2, Dhaka"},
                                 "items":[%s]}
                                """.formatted(advance, String.join(",", items))))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(r, "$.id")).longValue();
    }

    private long deliveredOrder(int advance, int unused, String... items) throws Exception {
        long id = order(advance, items);
        move(id, "{\"status\":\"DELIVERED\"}");
        return id;
    }

    private void move(long id, String json) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private long variant(String name, int qty) throws Exception {
        MvcResult r = mvc.perform(post("/api/stock/variants").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + " " + tag + "\",\"quantity\":" + qty + "}")).andExpect(status().isCreated()).andReturn();
        return ((Number) read(r, "$.id")).longValue();
    }

    private int stock(long variant) {
        return jdbc.sql("select quantity from stock_variants where id = :id").param("id", variant).query(Integer.class).single();
    }

    private List<Long> itemIds(long orderId) {
        return jdbc.sql("select id from order_items where order_id = :id order by position").param("id", orderId).query(Long.class).list();
    }

    private MvcResult summary() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Dhaka"));
        return mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)
                .param("from", today.toString()).param("to", today.toString())).andExpect(status().isOk()).andReturn();
    }

    private static double delta(MvcResult before, MvcResult after, String path) throws Exception {
        return ((Number) read(after, path)).doubleValue() - ((Number) read(before, path)).doubleValue();
    }
}
