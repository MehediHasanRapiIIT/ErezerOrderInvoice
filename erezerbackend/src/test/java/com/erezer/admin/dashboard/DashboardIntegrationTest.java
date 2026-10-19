package com.erezer.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the dashboard against a hand-calculated cash flow. Tests share one database, so each test compares
 * the summary before and after creating its own data instead of asserting absolute totals.
 */
class DashboardIntegrationTest extends IntegrationTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final String[] MONEY_FIELDS = {"totalRevenue", "productRevenue", "deliveryCharges", "returnRevenue",
            "returnProductRevenue", "returnDeliveryTotal", "refunds", "expenses", "netProfit"};

    @Autowired
    JdbcClient jdbc;

    @Test
    void figuresMatchTheRealCashFlowOfACodBusiness() throws Exception {
        String admin = adminBearer();
        LocalDate today = LocalDate.now(DHAKA);
        Map<String, Double> before = totals(admin, today, today);
        long ordersBefore = ((Number) JsonPath.read(summary(admin, today, today), "$.current.deliveredOrders")).longValue();

        // A: 1000 product − 100 discount + 60 delivery = 960, delivered.
        long a = createOrder(admin, 1000, 1, 60, 100);
        advance(admin, a, "{\"status\":\"DELIVERED\"}");

        // B: 2 × 500 + 120 delivery = 1120, refused at the door; the courier charges 60 to bring it back.
        long b = createOrder(admin, 500, 2, 120, 0);
        advance(admin, b, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"T1\"}");
        advance(admin, b, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":60}");

        // C: 800 + 60 delivery = 860, delivered, then returned by the customer; return charge 60.
        long c = createOrder(admin, 800, 1, 60, 0);
        advance(admin, c, "{\"status\":\"DELIVERED\"}");
        advance(admin, c, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":60}");

        createExpense(admin, today, "500");

        Map<String, Double> after = totals(admin, today, today);
        // Hand calculation:
        //   A  +960 collected − 60 courier                          = +900
        //   B  −120 delivery − 60 return (customer paid nothing)    = −180
        //   C  +860 − 60 courier − 800 refund − 60 return           =  −60
        //   cash from orders 660 − expenses 500                     = 160 net profit
        assertThat(delta(before, after, "totalRevenue")).isEqualTo(1820.0);      // 960 + 860
        assertThat(delta(before, after, "productRevenue")).isEqualTo(1700.0);    // 900 + 800
        assertThat(delta(before, after, "deliveryCharges")).isEqualTo(120.0);    // 60 + 60
        // Refused: the whole order total; after delivery: the products' value (the delivery charge is not refunded).
        assertThat(delta(before, after, "returnRevenue")).isEqualTo(1920.0);     // 1120 + 800
        assertThat(delta(before, after, "returnProductRevenue")).isEqualTo(1800.0); // 1000 + 800
        assertThat(delta(before, after, "returnDeliveryTotal")).isEqualTo(240.0); // (120 + 60) + 60
        assertThat(delta(before, after, "refunds")).isEqualTo(800.0);
        assertThat(delta(before, after, "expenses")).isEqualTo(500.0);
        assertThat(delta(before, after, "netProfit")).isEqualTo(160.0);

        String json = summary(admin, today, today);
        long ordersAfter = ((Number) JsonPath.read(json, "$.current.deliveredOrders")).longValue();
        assertThat(ordersAfter - ordersBefore).isEqualTo(2); // A and C were delivered today
    }

    @Test
    void aSaleStaysInItsDeliveryPeriodWhenReturnedLater() throws Exception {
        String admin = adminBearer();
        LocalDate today = LocalDate.now(DHAKA);
        LocalDate lastWeek = today.minusDays(7);

        long order = createOrder(admin, 1500, 1, 60, 0);
        advance(admin, order, "{\"status\":\"DELIVERED\"}");
        // Pretend it was delivered a week ago (the API always stamps "now").
        jdbc.sql("update orders set delivered_at = delivered_at - interval '7 days' where id = :id")
                .param("id", order).update();

        Map<String, Double> pastBefore = totals(admin, lastWeek, lastWeek);
        Map<String, Double> todayBefore = totals(admin, today, today);
        advance(admin, order, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":60}");
        Map<String, Double> pastAfter = totals(admin, lastWeek, lastWeek);
        Map<String, Double> todayAfter = totals(admin, today, today);

        // Last week's figures are history and must not change.
        for (String field : MONEY_FIELDS) {
            assertThat(delta(pastBefore, pastAfter, field)).as(field).isZero();
        }
        // Today books the refund and the return charge, not a negative sale.
        assertThat(delta(todayBefore, todayAfter, "productRevenue")).isZero();
        assertThat(delta(todayBefore, todayAfter, "refunds")).isEqualTo(1500.0);
        assertThat(delta(todayBefore, todayAfter, "returnDeliveryTotal")).isEqualTo(60.0);
        assertThat(delta(todayBefore, todayAfter, "netProfit")).isEqualTo(-1560.0);
    }

    @Test
    void comparesWithThePreviousPeriodAndBucketsLongRangesByMonth() throws Exception {
        String admin = adminBearer();
        LocalDate to = LocalDate.of(2026, 3, 31);
        String json = summary(admin, LocalDate.of(2026, 1, 1), to);
        assertThat((String) JsonPath.read(json, "$.granularity")).isEqualTo("MONTH");
        assertThat((Integer) JsonPath.read(json, "$.series.length()")).isEqualTo(3);
        assertThat((String) JsonPath.read(json, "$.series[0].bucket")).isEqualTo("2026-01-01");

        String week = summary(admin, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7));
        assertThat((String) JsonPath.read(week, "$.granularity")).isEqualTo("DAY");
        assertThat((Integer) JsonPath.read(week, "$.series.length()")).isEqualTo(7);
    }

    @Test
    void rejectsInvalidRangesAndRequiresPermission() throws Exception {
        mvc.perform(get("/api/dashboard/summary").param("from", "2026-05-02").param("to", "2026-05-01")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mvc.perform(get("/api/dashboard/summary").header("Authorization", bearer(Role.MODERATOR, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
    }

    private String summary(String admin, LocalDate from, LocalDate to) throws Exception {
        MvcResult result = mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private Map<String, Double> totals(String admin, LocalDate from, LocalDate to) throws Exception {
        String json = summary(admin, from, to);
        Map<String, Double> values = new java.util.HashMap<>();
        for (String field : MONEY_FIELDS) {
            values.put(field, ((Number) JsonPath.read(json, "$.current." + field)).doubleValue());
        }
        return values;
    }

    private static double delta(Map<String, Double> before, Map<String, Double> after, String field) {
        return Math.round((after.get(field) - before.get(field)) * 100) / 100.0;
    }

    private long createOrder(String admin, int unitPrice, int quantity, int delivery, int discount) throws Exception {
        String json = """
                {"paymentMethod":"CASH","deliveryCharge":%d,"discount":%d,
                 "customer":{"name":"Dash Test","phone":"01700000000","address":"Dhaka"},
                 "items":[{"productName":"Item","productCode":"DB-1","unitPrice":%d,"quantity":%d,"imageIds":[]}]}
                """.formatted(delivery, discount, unitPrice, quantity);
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private void advance(String admin, long id, String body) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void createExpense(String admin, LocalDate date, String amount) throws Exception {
        MvcResult categories = mvc.perform(get("/api/expense-categories").header("Authorization", admin)).andReturn();
        Number categoryId = read(categories, "$[0].id");
        mvc.perform(post("/api/expenses").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"%s","categoryId":%s,"description":"Dashboard test","amount":%s,
                                 "paymentMethod":"CASH"}
                                """.formatted(date, categoryId, amount)))
                .andExpect(status().isCreated());
    }
}
