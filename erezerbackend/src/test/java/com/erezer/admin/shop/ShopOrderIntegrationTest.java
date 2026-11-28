package com.erezer.admin.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.storage.StorageService;
import com.erezer.admin.support.IntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** Orders sent by the Erezer shop, with their photos, and the key they come with. */
class ShopOrderIntegrationTest extends IntegrationTest {

    private String admin;

    @MockitoBean
    StorageService storage;

    /** Photos kept in memory, so the invoice can print them. */
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();

    @BeforeEach
    void login() throws Exception {
        admin = adminBearer();
        doAnswer(inv -> {
            stored.put(inv.getArgument(0), ((InputStream) inv.getArgument(1)).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any(InputStream.class), anyLong(), anyString());
        when(storage.get(anyString())).thenAnswer(inv -> new ByteArrayInputStream(stored.get((String) inv.getArgument(0))));
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/photo").toURL());
        when(storage.isAvailable()).thenReturn(true);
    }

    @Test
    void anOrderArrivesWithItsPhotosAndIsNotAddedTwice() throws Exception {
        String key = newKey();
        String number = "EZ-" + ThreadLocalRandom.current().nextInt(100000, 999999);

        // Two photos for the first line: one real, one that isn't an image. The order still arrives.
        MvcResultHolder sent = send(key, number, new MockMultipartFile("item-0", "front.png", "image/png", png()),
                new MockMultipartFile("item-0", "notes.txt", "text/plain", "not a picture".getBytes()));
        sent.actions.andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.missingPhotos").value(1));
        long id = ((Number) read(sent.actions.andReturn(), "$.orderId")).longValue();

        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.orderNumber").value(number))
                .andExpect(jsonPath("$.platform").value("WEBSITE"))
                .andExpect(jsonPath("$.acceptedByOther").value("Erezer website"))
                .andExpect(jsonPath("$.paymentMethod").value("BKASH"))
                .andExpect(jsonPath("$.advance").value(1060.0))
                .andExpect(jsonPath("$.due").value(0.0))
                .andExpect(jsonPath("$.items[0].productName").value("Drop Shoulder Tee"))
                .andExpect(jsonPath("$.items[0].images.length()").value(1))
                .andExpect(jsonPath("$.items[0].images[0].description").value("Black · Drop Shoulder · L"));

        // The invoice prints with the photo.
        mvc.perform(get("/api/orders/" + id + "/invoice.pdf").header("Authorization", admin)).andExpect(status().isOk());

        // Sent again: found, not made twice.
        send(key, number).actions.andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("EXISTS"))
                .andExpect(jsonPath("$.orderId").value(id));
    }

    @Test
    void onlyTheCurrentKeyIsAccepted() throws Exception {
        String number = "EZ-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        send(null, number).actions.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SHOP_KEY_INVALID"));
        send("ezs_wrong", number).actions.andExpect(status().isUnauthorized());

        String old = newKey();
        String current = newKey();
        send(old, number).actions.andExpect(status().isUnauthorized());
        send(current, number).actions.andExpect(status().isCreated());
        mvc.perform(get("/api/integrations/shop/ping").header("X-Shop-Key", current)).andExpect(status().isOk());
        mvc.perform(get("/api/integrations/shop/ping").header("X-Shop-Key", old)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/shop-connection").header("Authorization", admin))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.keyHint").value("…" + current.substring(current.length() - 4)));

        mvc.perform(delete("/api/shop-connection/key").header("Authorization", admin)).andExpect(status().isNoContent());
        send(current, "EZ-" + ThreadLocalRandom.current().nextInt(100000, 999999)).actions.andExpect(status().isUnauthorized());
    }

    @Test
    void theKeyIsForAdminsOnly() throws Exception {
        String moderator = bearer(Role.MODERATOR, Permission.ORDER_VIEW, Permission.ORDER_CREATE);
        mvc.perform(post("/api/shop-connection/key").header("Authorization", moderator)).andExpect(status().isForbidden());
        mvc.perform(get("/api/shop-connection").header("Authorization", moderator)).andExpect(status().isForbidden());
    }

    @Test
    void aBadOrderIsRefusedWithTheReason() throws Exception {
        String key = newKey();
        // Paid more than the order costs.
        String body = order("EZ-" + ThreadLocalRandom.current().nextInt(100000, 999999)).replace("\"advance\":1060", "\"advance\":5000");
        mvc.perform(multipart("/api/integrations/shop/orders").file(new MockMultipartFile("order", "", "application/json", body.getBytes()))
                        .header("X-Shop-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ---- helpers ----

    private record MvcResultHolder(ResultActions actions) {
    }

    private MvcResultHolder send(String key, String number, MockMultipartFile... photos) throws Exception {
        var request = multipart("/api/integrations/shop/orders")
                .file(new MockMultipartFile("order", "", "application/json", order(number).getBytes()));
        for (MockMultipartFile photo : photos) {
            request.file(photo);
        }
        if (key != null) {
            request.header("X-Shop-Key", key);
        }
        return new MvcResultHolder(mvc.perform(request));
    }

    private String newKey() throws Exception {
        String key = read(mvc.perform(post("/api/shop-connection/key").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn(), "$.key");
        assertThat(key).startsWith("ezs_");
        return key;
    }

    /** A paid bKash order: 2 × 500 + 60 delivery, all paid in advance. */
    private static String order(String number) {
        return """
                {"orderNumber":"%s","orderedAt":"2026-11-20T08:30:00Z","paymentMethod":"BKASH","advance":1060,
                 "customer":{"name":"Rahim Uddin","phone":"+8801712345678","email":"rahim@example.com","address":"House 1, Road 2, Dhanmondi"},
                 "deliveryCharge":60,"discount":0,
                 "items":[{"productName":"Drop Shoulder Tee","productCode":"TS-00012","description":"Drop Shoulder",
                           "color":"Black","size":"L","unitPrice":500,"quantity":2,"imageDescription":"Black · Drop Shoulder · L"}]}
                """.formatted(number);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
