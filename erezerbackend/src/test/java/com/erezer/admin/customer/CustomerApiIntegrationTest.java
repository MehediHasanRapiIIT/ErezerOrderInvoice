package com.erezer.admin.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class CustomerApiIntegrationTest extends IntegrationTest {

    private String admin;
    /** 8 random digits so this test's customer is unique in the shared database, e.g. 019 + 12345678. */
    private String local;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminBearer();
        local = "019" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    @Test
    void ordersWithDifferentlyFormattedPhonesAreOneCustomer() throws Exception {
        String intl = "+880 " + local.substring(1, 5) + "-" + local.substring(5); // +880 1912-345678
        long first = createOrder("Rafi Ahmed", intl, "Mirpur 2, Dhaka", 1000, 60);
        advance(first, "{\"status\":\"DELIVERED\"}");
        long second = createOrder("Rafi Ahmed", "88" + local, "Mirpur 2, Dhaka", 500, 60);
        advance(second, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"T\"}");
        advance(second, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":60}");
        long third = createOrder("Rafi A.", local, "Uttara, Dhaka", 800, 60); // latest order: new name and address
        advance(third, "{\"status\":\"CANCELLED\"}");

        mvc.perform(get("/api/customers").header("Authorization", admin).param("q", local))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].phoneKey").value(local))
                .andExpect(jsonPath("$.content[0].name").value("Rafi A."))            // from the latest order
                .andExpect(jsonPath("$.content[0].address").value("Uttara, Dhaka"))
                .andExpect(jsonPath("$.content[0].orders").value(3))
                .andExpect(jsonPath("$.content[0].delivered").value(1))
                .andExpect(jsonPath("$.content[0].returned").value(1))
                .andExpect(jsonPath("$.content[0].cancelled").value(1))
                .andExpect(jsonPath("$.content[0].totalPaid").value(1060.0));        // only the delivered order
    }

    @Test
    void searchMatchesAnyPhoneFormattingAndSegmentsFilter() throws Exception {
        long id = createOrder("Nadia Islam", local, "Dhanmondi", 900, 60);
        advance(id, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"T\"}");
        advance(id, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":60}");

        String spaced = "+880 " + local.substring(1, 5) + " " + local.substring(5);
        mvc.perform(get("/api/customers").header("Authorization", admin).param("q", spaced))
                .andExpect(jsonPath("$.content[0].phoneKey").value(local));
        mvc.perform(get("/api/customers").header("Authorization", admin).param("q", local).param("segment", "returns"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/customers").header("Authorization", admin).param("q", local).param("segment", "repeat"))
                .andExpect(jsonPath("$.totalElements").value(0)); // only one order so far
        mvc.perform(get("/api/customers").header("Authorization", admin).param("segment", "vip"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void orderSearchFindsACustomersOrdersWhateverThePhoneFormatting() throws Exception {
        createOrder("Format Test", "+880 " + local.substring(1, 5) + "-" + local.substring(5), "Dhaka", 100, 60);
        createOrder("Format Test", local, "Dhaka", 100, 60);
        mvc.perform(get("/api/orders").header("Authorization", admin).param("q", local))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void lookupRecognisesAReturningCustomerInAnyPhoneFormat() throws Exception {
        long id = createOrder("Karim Uddin", local, "Mohammadpur, Dhaka", 1500, 60);
        advance(id, "{\"status\":\"SHIPPED\",\"courier\":\"Pathao\",\"trackingNumber\":\"T\"}");
        advance(id, "{\"status\":\"RETURNED\",\"returnDeliveryCharge\":60}");

        String orderTaker = bearer(Role.MODERATOR, Permission.ORDER_CREATE); // no Customers permission
        mvc.perform(get("/api/customers/lookup").header("Authorization", orderTaker)
                        .param("phone", "+880 " + local.substring(1, 5) + "-" + local.substring(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Karim Uddin"))
                .andExpect(jsonPath("$.address").value("Mohammadpur, Dhaka"))
                .andExpect(jsonPath("$.returned").value(1));

        // Unknown or incomplete numbers reveal nothing.
        mvc.perform(get("/api/customers/lookup").header("Authorization", orderTaker).param("phone", "01999999999"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/customers/lookup").header("Authorization", orderTaker).param("phone", local.substring(0, 7)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/customers/lookup").header("Authorization", bearer(Role.MODERATOR, Permission.EXPENSE_VIEW))
                        .param("phone", local))
                .andExpect(status().isForbidden());
    }

    @Test
    void summaryCountsAllCustomers() throws Exception {
        MvcResult before = mvc.perform(get("/api/customers").header("Authorization", admin)).andReturn();
        long customersBefore = ((Number) read(before, "$.summary.customers")).longValue();
        createOrder("New Person", local, "Gulshan", 100, 60);
        MvcResult after = mvc.perform(get("/api/customers").header("Authorization", admin)).andReturn();
        assertThat(((Number) read(after, "$.summary.customers")).longValue()).isEqualTo(customersBefore + 1);
    }

    @Test
    void exportNeedsItsOwnPermissionAndKeepsPhoneAsText() throws Exception {
        createOrder("Export Me", local, "Banani", 700, 60);

        String viewer = bearer(Role.MODERATOR, Permission.CUSTOMER_VIEW);
        mvc.perform(get("/api/customers").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/customers/export.xlsx").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/customers").header("Authorization", bearer(Role.MODERATOR, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());

        MvcResult file = mvc.perform(get("/api/customers/export.xlsx").header("Authorization", admin).param("q", local))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheet("Customers");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Export Me");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo(local); // leading zero kept
        }
    }

    private long createOrder(String name, String phone, String address, int price, int delivery) throws Exception {
        String json = """
                {"paymentMethod":"CASH","deliveryCharge":%d,
                 "customer":{"name":"%s","phone":"%s","address":"%s"},
                 "items":[{"productName":"Shirt","productCode":"C-1","unitPrice":%d,"quantity":1,"imageIds":[]}]}
                """.formatted(delivery, name, phone, address, price);
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private void advance(long id, String body) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
