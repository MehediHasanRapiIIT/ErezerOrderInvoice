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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class ExchangeIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;

    @BeforeEach
    void login() throws Exception {
        admin = adminBearer();
    }

    @Test
    void sizeExchangeOfASingleItemCustomerPaysDelivery() throws Exception {
        long original = deliveredOrder(0, item("Regular Fit T-Shirt", "TS-1", "M", 1000, 1));
        long itemId = itemIds(original).getFirst();

        MvcResult created = exchange(original, admin, """
                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":120,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(itemId, replacement("Regular Fit T-Shirt", "TS-1", "L", 1000)))
                .andExpect(status().isCreated()).andReturn();
        long ex = id(created);

        mvc.perform(get("/api/orders/" + ex).header("Authorization", admin))
                .andExpect(jsonPath("$.orderType").value("EXCHANGE"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.customer.phone").value("01711222333"))
                .andExpect(jsonPath("$.items[0].size").value("L"))
                .andExpect(jsonPath("$.subtotal").value(1000.0))
                .andExpect(jsonPath("$.deliveryCharge").value(120.0))
                .andExpect(jsonPath("$.total").value(120.0))                    // difference 0 + delivery 120
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.exchange.ofOrderId").value(original))
                .andExpect(jsonPath("$.exchange.credit").value(1000.0))
                .andExpect(jsonPath("$.exchange.priceDifference").value(0.0))
                .andExpect(jsonPath("$.exchange.deliveryPaidBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.exchange.reasonLabel").value("Wrong size"))
                .andExpect(jsonPath("$.exchange.returnedItems[0].size").value("M"))
                .andExpect(jsonPath("$.history[1].note").value(startsWith("Exchange of EZ-")));
        mvc.perform(get("/api/orders/" + original).header("Authorization", admin))
                .andExpect(jsonPath("$.orderType").value("SALE"))
                .andExpect(jsonPath("$.exchanges[0].id").value(ex))
                .andExpect(jsonPath("$.notes[0].body").value(startsWith("Exchange EZ-")))
                .andExpect(jsonPath("$.editable").value(true));            // flag only; edits are refused below

        // The exchange is edited like any order and its money is worked out again: a pricier replacement is collected.
        String edit = orderJson(0, item("Regular Fit T-Shirt", "TS-1", "L", 1300, 1), 0).replace("\"deliveryCharge\":60", "\"deliveryCharge\":120");
        mvc.perform(put("/api/orders/" + ex).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(edit.replace("\"discount\":0", "\"discount\":50").replace("}\n", ",\"version\":" + version(ex) + "}\n")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCHANGE_DISCOUNT"));
        mvc.perform(put("/api/orders/" + ex).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(edit.replace("}\n", ",\"version\":" + version(ex) + "}\n")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + ex).header("Authorization", admin))
                .andExpect(jsonPath("$.orderType").value("EXCHANGE"))
                .andExpect(jsonPath("$.exchange.priceDifference").value(300.0))
                .andExpect(jsonPath("$.total").value(420.0));                   // difference 300 + delivery 120

        // The exchanged order itself keeps its items.
        mvc.perform(put("/api/orders/" + original).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(0, item("X", "X-1", "M", 1, 1), 0).replace("}\n", ",\"version\":" + version(original) + "}\n")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(startsWith("This order has exchanges")));
    }

    @Test
    void anExchangeCanUseItsOwnOrderId() throws Exception {
        long original = deliveredOrder(0, item("T-Shirt", "TS-1", "M", 1000, 1));
        long itemId = itemIds(original).getFirst();
        String number = "EX-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long ex = id(exchange(original, admin, """
                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":60,"refund":false,"orderNumber":"%s",
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(number.toLowerCase(), itemId, replacement("T-Shirt", "TS-1", "L", 1000)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + ex).header("Authorization", admin)).andExpect(jsonPath("$.orderNumber").value(number));
        // An Order ID already in use is refused.
        exchange(original, admin, """
                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":60,"refund":false,"orderNumber":"%s",
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(number, itemId, replacement("T-Shirt", "TS-1", "L", 1000)))
                .andExpect(status().isConflict());
    }

    @Test
    void twoItemsExchangeOneForAPricierProductErezerPaysDelivery() throws Exception {
        long original = deliveredOrder(0, item("T-Shirt", "TS-1", "M", 1000, 1), item("Cargo Pant", "PN-1", "32", 1500, 1));
        List<Long> items = itemIds(original);

        long ex = id(exchange(original, admin, """
                {"reason":"DEFECTIVE","deliveryPaidBy":"EREZER","deliveryCharge":120,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(items.get(0), replacement("Premium T-Shirt", "TS-9", "M", 1200)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + ex).header("Authorization", admin))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.exchange.credit").value(1000.0))
                .andExpect(jsonPath("$.exchange.priceDifference").value(200.0))
                .andExpect(jsonPath("$.total").value(200.0));                   // Erezer pays the delivery

        mvc.perform(get("/api/orders/" + original + "/exchange-options").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].remaining").value(0))
                .andExpect(jsonPath("$.items[0].alreadyExchanged").value(1))
                .andExpect(jsonPath("$.items[1].remaining").value(1))
                .andExpect(jsonPath("$.exchanges[0].id").value(ex));
        // The same unit cannot be exchanged twice; the pant still can.
        exchange(original, admin, simpleExchange(items.get(0), 1, 1000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("T-Shirt has already been exchanged or returned"));
        exchange(original, admin, simpleExchange(items.get(1), 1, 1500)).andExpect(status().isCreated());
    }

    @Test
    void partOfAQuantityAndThreeItemOrders() throws Exception {
        long original = deliveredOrder(0, item("T-Shirt", "TS-1", "M", 500, 2), item("Cap", "CP-1", null, 300, 1),
                item("Sock", "SK-1", null, 100, 3));
        List<Long> items = itemIds(original);

        long ex = id(exchange(original, admin, """
                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":100,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s},
                          {"originalItemId":%d,"quantity":2,"replacement":%s}]}
                """.formatted(items.get(0), replacement("T-Shirt", "TS-1", "L", 500),
                        items.get(2), replacement("Sock", "SK-1", "L", 100)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + ex).header("Authorization", admin))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].quantity").value(2))             // ships as many as are taken back
                .andExpect(jsonPath("$.exchange.credit").value(700.0))           // 500 + 2 × 100
                .andExpect(jsonPath("$.total").value(100.0));
        exchange(original, admin, simpleExchange(items.get(0), 2, 500))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Only 1 of T-Shirt can still be exchanged"));
    }

    @Test
    void cheaperReplacementRefundOrNotAndDiscountIsShared() throws Exception {
        // 2 × 1000 with a 200 discount: each unit was really paid 900.
        long original = deliveredOrder(200, item("T-Shirt", "TS-1", "M", 1000, 1), item("Polo", "PL-1", "M", 1000, 1));
        List<Long> items = itemIds(original);
        mvc.perform(get("/api/orders/" + original + "/exchange-options").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].unitCredit").value(900.0));

        long refunded = id(exchange(original, admin, """
                {"reason":"CHANGED_MIND","deliveryPaidBy":"CUSTOMER","deliveryCharge":120,"refund":true,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(items.get(0), replacement("Basic Tee", "TS-0", "M", 600)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + refunded).header("Authorization", admin))
                .andExpect(jsonPath("$.exchange.priceDifference").value(-300.0))
                .andExpect(jsonPath("$.exchange.refundAmount").value(300.0))
                .andExpect(jsonPath("$.total").value(120.0));                   // never negative: refund is separate

        long kept = id(exchange(original, admin, """
                {"reason":"CHANGED_MIND","deliveryPaidBy":"CUSTOMER","deliveryCharge":120,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(items.get(1), replacement("Basic Polo", "PL-0", "M", 600)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/orders/" + kept).header("Authorization", admin))
                .andExpect(jsonPath("$.exchange.refundAmount").value(0.0))
                .andExpect(jsonPath("$.total").value(120.0));
    }

    @Test
    void noTimeLimitAnyStaffWithThePermissionCanExchangeAnOldOrder() throws Exception {
        long original = deliveredOrder(0, item("T-Shirt", "TS-1", "M", 1000, 1));
        jdbc.sql("update orders set delivered_at = :at where id = :id")
                .param("at", java.sql.Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS))).param("id", original).update();
        String moderator = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.EXCHANGE_CREATE);
        mvc.perform(get("/api/orders/" + original + "/exchange-options").header("Authorization", moderator))
                .andExpect(status().isOk());
        exchange(original, moderator, simpleExchange(itemIds(original).getFirst(), 1, 1000)).andExpect(status().isCreated());
    }

    @Test
    void onlyDeliveredOrdersAndCancelledExchangesFreeTheirUnits() throws Exception {
        long notDelivered = createOrder(0, item("T-Shirt", "TS-1", "M", 1000, 1));
        mvc.perform(get("/api/orders/" + notDelivered + "/exchange-options").header("Authorization", admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_EXCHANGEABLE"));

        long original = deliveredOrder(0, item("T-Shirt", "TS-1", "M", 1000, 1));
        long itemId = itemIds(original).getFirst();
        long ex = id(exchange(original, admin, simpleExchange(itemId, 1, 1000)).andExpect(status().isCreated()).andReturn());
        setStatus(ex, "CANCELLED");
        mvc.perform(get("/api/orders/" + original + "/exchange-options").header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].remaining").value(1));
        exchange(original, admin, simpleExchange(itemId, 1, 1000)).andExpect(status().isCreated());

        String noPermission = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_CREATE);
        exchange(original, noPermission, simpleExchange(itemId, 1, 1000)).andExpect(status().isForbidden());
    }

    @Test
    void lookupByOrderNumberOrPhone() throws Exception {
        String phone = "0171" + (1_000_000 + (int) (Math.random() * 8_999_999));
        long original = deliveredOrderFor(phone, item("T-Shirt", "TS-1", "M", 1000, 1));
        createOrderFor(phone, item("Not delivered", "ND-1", "M", 1000, 1));
        String number = orderNumber(original);
        mvc.perform(get("/api/exchanges/lookup").param("q", number.toLowerCase()).header("Authorization", admin))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(original));
        mvc.perform(get("/api/exchanges/lookup").param("q", "+88" + phone).header("Authorization", admin))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].orderNumber").value(number));
    }

    @Test
    void dashboardCountsExchangedItemsAndTheRealMoney() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Dhaka"));
        MvcResult before = summary(today);

        long original = deliveredOrder(0, item("T-Shirt", "TS-1", "M", 1000, 2), item("Polo", "PL-1", "M", 800, 1));
        List<Long> items = itemIds(original);
        MvcResult afterSale = summary(today);
        double profitAfterSale = number(afterSale, "$.current.netProfit");

        // A: 2 tees → pricier tees (+200 each), Erezer pays 120 delivery (courier really charges 110).
        long a = id(exchange(original, admin, """
                {"reason":"DEFECTIVE","deliveryPaidBy":"EREZER","deliveryCharge":120,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":2,"replacement":%s}]}
                """.formatted(items.get(0), replacement("Premium Tee", "TS-9", "M", 1200))).andExpect(status().isCreated()).andReturn());
        // B: polo → cheaper polo with a 300 refund, customer pays 100 delivery.
        long b = id(exchange(original, admin, """
                {"reason":"CHANGED_MIND","deliveryPaidBy":"CUSTOMER","deliveryCharge":100,"refund":true,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(items.get(1), replacement("Basic Polo", "PL-0", "M", 500))).andExpect(status().isCreated()).andReturn());
        // Not counted until delivered.
        assertThat(number(summary(today), "$.exchanges.items")).isEqualTo(number(before, "$.exchanges.items"));

        setStatus(a, "DELIVERED");
        setStatus(b, "DELIVERED");
        jdbc.sql("update orders set courier_charge = 110 where id = :id").param("id", a).update();

        MvcResult after = summary(today);
        assertThat(number(after, "$.exchanges.items") - number(before, "$.exchanges.items")).isEqualTo(3);
        assertThat(number(after, "$.exchanges.itemsPaidByErezer") - number(before, "$.exchanges.itemsPaidByErezer")).isEqualTo(2);
        assertThat(number(after, "$.exchanges.itemsPaidByCustomer") - number(before, "$.exchanges.itemsPaidByCustomer")).isEqualTo(1);
        assertThat(number(after, "$.exchanges.erezerCost") - number(before, "$.exchanges.erezerCost")).isEqualTo(110.0);
        assertThat(number(after, "$.exchanges.deliveryCollected") - number(before, "$.exchanges.deliveryCollected")).isEqualTo(100.0);
        assertThat(number(after, "$.current.exchangeRefunds") - number(afterSale, "$.current.exchangeRefunds")).isEqualTo(300.0);
        assertThat(number(after, "$.current.exchangeCost") - number(afterSale, "$.current.exchangeCost")).isEqualTo(110.0);
        // Profit change from the exchanges: +400 price difference − 110 courier − 300 refund = −10.
        assertThat(number(after, "$.current.netProfit") - profitAfterSale).isEqualTo(-10.0);
        // Product revenue grows only by the price difference, never by the replacement's full price.
        assertThat(number(after, "$.current.productRevenue") - number(afterSale, "$.current.productRevenue")).isEqualTo(400.0);
    }

    @Test
    void exchangeParcelTellsTheRiderAndInvoiceShowsTheExchange() throws Exception {
        long original = deliveredOrder(0, item("Regular Fit T-Shirt", "TS-1", "M", 1000, 1));
        long ex = id(exchange(original, admin, """
                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":120,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":1,"replacement":%s}]}
                """.formatted(itemIds(original).getFirst(), replacement("Regular Fit T-Shirt", "TS-1", "L", 1100)))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(post("/api/courier/preview").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[" + ex + "]}"))
                .andExpect(jsonPath("$.parcels[0].codAmount").value(220.0))     // 100 difference + 120 delivery
                .andExpect(jsonPath("$.parcels[0].note").value("EXCHANGE - collect the old item(s): Regular Fit T-Shirt (White / M) ×1"))
                .andExpect(jsonPath("$.parcels[0].itemDescription").value("Regular Fit T-Shirt (L) ×1"));

        byte[] pdf = mvc.perform(get("/api/orders/" + ex + "/invoice.pdf").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains("EXCHANGE", "EXCHANGE OF", orderNumber(original), "RETURNED ITEMS", "Regular Fit T-Shirt (White / M)",
                "Price difference", "100.00", "Amount to collect", "220.00");
    }

    // ---- helpers ----

    private ResultActions exchange(long orderId, String bearer, String json) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/exchanges").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String simpleExchange(long itemId, int quantity, int price) {
        return """
                {"reason":"WRONG_SIZE","deliveryPaidBy":"CUSTOMER","deliveryCharge":100,"refund":false,
                 "items":[{"originalItemId":%d,"quantity":%d,"replacement":%s}]}
                """.formatted(itemId, quantity, replacement("Replacement", "RP-1", "L", price));
    }

    private static String replacement(String name, String code, String size, int price) {
        return """
                {"productName":"%s","productCode":"%s","size":"%s","unitPrice":%d,"quantity":1,"imageIds":[]}
                """.formatted(name, code, size, price);
    }

    private static String item(String name, String code, String size, int price, int quantity) {
        return """
                {"productName":"%s","productCode":"%s","color":"White",%s"unitPrice":%d,"quantity":%d,"imageIds":[]}
                """.formatted(name, code, size == null ? "" : "\"size\":\"" + size + "\",", price, quantity);
    }

    private static String orderJson(int discount, String phone, String... items) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":60,"discount":%d,
                 "customer":{"name":"Exchange Test %s","phone":"%s","address":"Mirpur-2, Dhaka"},
                 "items":[%s]}
                """.formatted(discount, UUID.randomUUID().toString().substring(0, 4), phone, String.join(",", items));
    }

    private static String orderJson(int discount, String item, int unused) {
        return orderJson(discount, "01711222333", item);
    }

    private long createOrder(int discount, String... items) throws Exception {
        return createOrderWith(discount, "01711222333", items);
    }

    private long createOrderFor(String phone, String... items) throws Exception {
        return createOrderWith(0, phone, items);
    }

    private long createOrderWith(int discount, String phone, String... items) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson(discount, phone, items)))
                .andExpect(status().isCreated()).andReturn();
        return id(result);
    }

    private long deliveredOrder(int discount, String... items) throws Exception {
        long id = createOrder(discount, items);
        setStatus(id, "DELIVERED");
        return id;
    }

    private long deliveredOrderFor(String phone, String... items) throws Exception {
        long id = createOrderFor(phone, items);
        setStatus(id, "DELIVERED");
        return id;
    }

    private void setStatus(long id, String status) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private List<Long> itemIds(long orderId) {
        return jdbc.sql("select id from order_items where order_id = :id order by position")
                .param("id", orderId).query(Long.class).list();
    }

    private long version(long orderId) {
        return jdbc.sql("select version from orders where id = :id").param("id", orderId).query(Long.class).single();
    }

    private String orderNumber(long orderId) {
        return jdbc.sql("select order_number from orders where id = :id").param("id", orderId).query(String.class).single();
    }

    private MvcResult summary(LocalDate day) throws Exception {
        return mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)
                .param("from", day.toString()).param("to", day.toString())).andExpect(status().isOk()).andReturn();
    }

    private static double number(MvcResult result, String path) throws Exception {
        return ((Number) read(result, path)).doubleValue();
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) read(result, "$.id")).longValue();
    }
}
