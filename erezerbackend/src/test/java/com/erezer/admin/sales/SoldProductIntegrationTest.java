package com.erezer.admin.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class SoldProductIntegrationTest extends IntegrationTest {

    private String admin;
    private String tee;
    private String cap;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        tee = "Regular Fit T-Shirt " + tag;
        cap = "Cap " + tag;
    }

    @Test
    void categoriesShareOrderAmountsByPiecesAndCountOnlyDeliveredSales() throws Exception {
        // 2 tees (2,000) + 1 cap (400), ৳300 discount, ৳90 delivery, courier really charged ৳120 → tees 2/3, cap 1/3.
        long a = order(List.of(item(tee, 1000, 2), item(cap, 400, 1)), 300, 90);
        deliver(a);
        mvc.perform(put("/api/orders/" + a + "/courier-cost").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courierCharge\":120}")).andExpect(status().isOk());
        // Same category typed differently; courier charge unknown → the delivery charge stands in.
        long b = order(List.of(item("  " + tee.toLowerCase().replace(" ", "  ") + " ", 500, 1)), 0, 60);
        deliver(b);
        // Not counted: still in production, returned after delivery.
        order(List.of(item(tee, 999, 5)), 0, 60);
        long returned = order(List.of(item(tee, 999, 5)), 0, 60);
        deliver(returned);
        mvc.perform(post("/api/orders/" + returned + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETURNED\"}")).andExpect(status().isOk());

        MvcResult summary = mvc.perform(get("/api/sold-products/summary").header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> categories = read(summary, "$.categories");
        Map<String, Object> tees = categories.stream().filter(c -> c.get("categoryKey").equals(tee.toLowerCase())).findFirst().orElseThrow();
        assertThat(tees.get("category")).isEqualTo(tee);                     // most common spelling
        assertThat(((Number) tees.get("orders")).intValue()).isEqualTo(2);
        assertThat(((Number) tees.get("pieces")).intValue()).isEqualTo(3);
        assertThat(((Number) tees.get("sale")).doubleValue()).isEqualTo(1800 + 500.0);      // 2,000 − 200 discount share
        assertThat(((Number) tees.get("deliveryCharge")).doubleValue()).isEqualTo(60 + 60.0);
        assertThat(((Number) tees.get("courierCost")).doubleValue()).isEqualTo(80 + 60.0);
        Map<String, Object> caps = categories.stream().filter(c -> c.get("categoryKey").equals(cap.toLowerCase())).findFirst().orElseThrow();
        assertThat(((Number) caps.get("sale")).doubleValue()).isEqualTo(300.0);              // 400 − 100
        assertThat(((Number) caps.get("deliveryCharge")).doubleValue()).isEqualTo(30.0);
        assertThat(((Number) caps.get("courierCost")).doubleValue()).isEqualTo(40.0);

        // Filtered by category: one row per order, and totals for just that category.
        mvc.perform(get("/api/sold-products").param("category", tee).header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].category").value(tee));
        mvc.perform(get("/api/sold-products/summary").param("category", tee).header("Authorization", admin))
                .andExpect(jsonPath("$.totals.sale").value(2300.0))
                .andExpect(jsonPath("$.totals.courierCost").value(140.0));

        // Search by order ID.
        String number = read(mvc.perform(get("/api/orders/" + a).header("Authorization", admin)).andReturn(), "$.orderNumber");
        mvc.perform(get("/api/sold-products").param("q", number).header("Authorization", admin))
                .andExpect(jsonPath("$.totalElements").value(2));                  // tee row + cap row of order A

        // Suggestions for Add Order, and the Excel export.
        List<String> names = read(mvc.perform(get("/api/sold-products/product-names").header("Authorization", admin)).andReturn(), "$");
        assertThat(names).contains(tee, cap);
        byte[] xlsx = mvc.perform(get("/api/sold-products/export.xlsx").param("category", tee).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Orders").getLastRowNum()).isEqualTo(2);
            assertThat(wb.getSheet("Orders").getRow(1).getCell(2).getStringCellValue()).isEqualTo(tee);
        }
    }

    @Test
    void sharesAddUpToTheOrderExactlyDespiteRounding() throws Exception {
        String hoodie = "Hoodie " + tee;
        long id = order(List.of(item(tee, 900, 1), item(cap, 400, 1), item(hoodie, 1500, 1)), 100, 70);
        deliver(id);
        mvc.perform(put("/api/orders/" + id + "/courier-cost").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courierCharge\":100}")).andExpect(status().isOk());
        String number = read(mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andReturn(), "$.orderNumber");

        MvcResult rows = mvc.perform(get("/api/sold-products").param("q", number).header("Authorization", admin)).andReturn();
        List<Number> delivery = read(rows, "$.content[*].deliveryCharge");
        List<Number> courier = read(rows, "$.content[*].courierCost");
        List<Number> sale = read(rows, "$.content[*].sale");
        assertThat(delivery).hasSize(3);
        assertThat(delivery.stream().mapToDouble(Number::doubleValue).sum()).isEqualTo(70.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(courier.stream().mapToDouble(Number::doubleValue).sum()).isEqualTo(100.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(sale.stream().mapToDouble(Number::doubleValue).sum()).isEqualTo(2800 - 100.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void permissions() throws Exception {
        mvc.perform(get("/api/sold-products").header("Authorization", bearer(Role.MODERATOR, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/sold-products").header("Authorization", bearer(Role.MODERATOR, Permission.SALES_VIEW)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sold-products/product-names").header("Authorization", bearer(Role.MODERATOR, Permission.ORDER_CREATE)))
                .andExpect(status().isOk());
    }

    private long order(List<String> items, int discount, int delivery) throws Exception {
        MvcResult r = mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethod":"CASH","deliveryCharge":%d,"discount":%d,
                                 "customer":{"name":"Sold Test","phone":"01711000555","address":"Mirpur-2, Dhaka"},
                                 "items":[%s]}
                                """.formatted(delivery, discount, String.join(",", items))))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(r, "$.id")).longValue();
    }

    private static String item(String name, int price, int qty) {
        return "{\"productName\":\"%s\",\"unitPrice\":%d,\"quantity\":%d,\"imageIds\":[]}".formatted(name, price, qty);
    }

    private void deliver(long id) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERED\"}")).andExpect(status().isOk());
    }
}
