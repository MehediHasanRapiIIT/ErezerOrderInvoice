package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.storage.StorageService;
import com.erezer.admin.support.IntegrationTest;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Attaching files, returns counted once, and orders whose products are fixed by exchanges or returns. */
class OrderSafetyIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @MockitoBean
    StorageService storage;

    @Autowired
    JdbcClient jdbc;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/signed").toURL());
        admin = adminBearer();
    }

    @Test
    void someoneElsesFilesCantBeAttachedToAnOrder() throws Exception {
        // An admin's expense receipt photo…
        Number receipt = read(mvc.perform(multipart("/api/files/receipts").file(new MockMultipartFile("file", "bill.png", "image/png", PNG))
                .header("Authorization", admin)).andExpect(status().isOk()).andReturn(), "$.id");
        // …and an admin's product image can't be attached by an order taker.
        Number adminImage = upload(admin);
        String clerk = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_CREATE, Permission.ORDER_EDIT);
        createOrder(clerk, "[" + receipt + "]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        createOrder(clerk, "[" + adminImage + "]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_YOURS"));

        // Their own uploads are fine, and an order keeps its images when someone else edits it.
        Number own = upload(clerk);
        long id = id(createOrder(clerk, "[" + own + "]").andExpect(status().isCreated()).andReturn());
        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("[" + own + "]", "Edited Name", 900).replace("}\n", ",\"version\":" + version(id) + "}\n")))
                .andExpect(status().isOk());
    }

    @Test
    void returnedThenCancelledThenReturnedAgainCountsOneReturn() throws Exception {
        long id = id(createOrder(admin, "[]").andExpect(status().isCreated()).andReturn());
        setStatus(id, "{\"status\":\"SHIPPED\",\"courier\":\"X\",\"trackingNumber\":\"T-1\"}");
        setStatus(id, "{\"status\":\"RETURNED\",\"returnPaidBy\":\"EREZER\"}");
        setStatus(id, "{\"status\":\"CANCELLED\"}");
        setStatus(id, "{\"status\":\"RETURNED\",\"returnPaidBy\":\"EREZER\"}");
        assertThat(activeReturns(id)).isEqualTo(1);

        // The same after delivery: everything is already back, so no second (empty) return.
        long delivered = id(createOrder(admin, "[]").andExpect(status().isCreated()).andReturn());
        setStatus(delivered, "{\"status\":\"DELIVERED\"}");
        setStatus(delivered, "{\"status\":\"RETURNED\",\"returnPaidBy\":\"CUSTOMER\"}");
        setStatus(delivered, "{\"status\":\"CANCELLED\"}");
        setStatus(delivered, "{\"status\":\"RETURNED\",\"returnPaidBy\":\"CUSTOMER\"}");
        assertThat(activeReturns(delivered)).isEqualTo(1);
    }

    @Test
    void anOrderWithAReturnKeepsItsProductsButItsDetailsCanChange() throws Exception {
        long id = id(createOrder(admin, "[]").andExpect(status().isCreated()).andReturn());
        setStatus(id, "{\"status\":\"DELIVERED\"}");
        long itemId = jdbc.sql("select id from order_items where order_id = :id").param("id", id).query(Long.class).single();
        mvc.perform(post("/api/orders/" + id + "/returns").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":%d,\"quantity\":1}],\"reason\":\"WRONG_SIZE\",\"paidBy\":\"CUSTOMER\",\"returnCharge\":60}".formatted(itemId)))
                .andExpect(status().isCreated());

        // Same products, new customer name and delivery charge: fine.
        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("[]", "Corrected Name", 900).replace("\"deliveryCharge\":60", "\"deliveryCharge\":80")
                                .replace("}\n", ",\"version\":" + version(id) + "}\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.name").value("Corrected Name"))
                .andExpect(jsonPath("$.total").value(1880.0)); // 2 × 900 + 80

        // Changing a product is refused with a clear message.
        mvc.perform(put("/api/orders/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("[]", "Corrected Name", 950).replace("}\n", ",\"version\":" + version(id) + "}\n")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This order has exchanges or returns")));
    }

    private Number upload(String bearer) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/files/images").file(new MockMultipartFile("files", "p.png", "image/png", PNG))
                .header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
        return read(r, "$[0].id");
    }

    private ResultActions createOrder(String bearer, String imageIds) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(orderJson(imageIds, "Safety " + UUID.randomUUID().toString().substring(0, 4), 900)));
    }

    private static String orderJson(String imageIds, String name, int price) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":60,
                 "customer":{"name":"%s","phone":"01711000333","address":"Mirpur-2, Dhaka"},
                 "items":[{"productName":"T-Shirt","unitPrice":%d,"quantity":2,"imageIds":%s}]}
                """.formatted(name, price, imageIds);
    }

    private void setStatus(long id, String json) throws Exception {
        mvc.perform(post("/api/orders/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private long version(long id) {
        return jdbc.sql("select version from orders where id = :id").param("id", id).query(Long.class).single();
    }

    private long activeReturns(long id) {
        return jdbc.sql("select count(*) from order_returns where order_id = :id and cancelled_at is null").param("id", id)
                .query(Long.class).single();
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) read(result, "$.id")).longValue();
    }
}
