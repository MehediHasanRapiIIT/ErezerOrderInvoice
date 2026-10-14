package com.erezer.admin.file;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.storage.StorageService;
import com.erezer.admin.support.IntegrationTest;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

class ImageUploadIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @MockitoBean
    StorageService storage;

    @BeforeEach
    void stubStorage() throws Exception {
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/signed").toURL());
    }

    @Test
    void uploadsImageAndAttachesItToAnOrder() throws Exception {
        String admin = adminBearer();
        MvcResult upload = mvc.perform(multipart("/api/files/images")
                        .file(new MockMultipartFile("files", "front.png", "image/png", PNG))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contentType").value("image/png"))
                .andExpect(jsonPath("$[0].url").value("http://storage.test/signed"))
                .andReturn();
        int fileId = read(upload, "$[0].id");
        verify(storage).put(startsWithImages(), any(), eq((long) PNG.length), eq("image/png"));

        String order = """
                {"paymentMethod":"BKASH","deliveryCharge":60,
                 "customer":{"name":"Rafi","phone":"01600000000","address":"Dhaka"},
                 "items":[{"productName":"Pant","productCode":"PN-1","unitPrice":1200,"quantity":1,"imageIds":[%d]}]}
                """.formatted(fileId);
        MvcResult created = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isCreated())
                .andReturn();

        mvc.perform(get("/api/orders/" + read(created, "$.id")).header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].images[0].fileId").value(fileId))
                .andExpect(jsonPath("$.items[0].images[0].url").value(startsWith("http://storage.test")));
    }

    @Test
    void rejectsFilesThatAreNotImagesRegardlessOfDeclaredType() throws Exception {
        mvc.perform(multipart("/api/files/images")
                        .file(new MockMultipartFile("files", "evil.png", "image/png", "<script>".getBytes()))
                        .header("Authorization", adminBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));
    }

    @Test
    void orderRejectsUnknownImageIds() throws Exception {
        String order = """
                {"paymentMethod":"CASH","deliveryCharge":0,
                 "customer":{"name":"A","phone":"01600000000","address":"Dhaka"},
                 "items":[{"productName":"X","productCode":"X-1","unitPrice":10,"quantity":1,"imageIds":[999999]}]}
                """;
        mvc.perform(post("/api/orders").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
    }

    @Test
    void uploadRequiresCreateOrEditPermission() throws Exception {
        mvc.perform(multipart("/api/files/images")
                        .file(new MockMultipartFile("files", "a.png", "image/png", PNG))
                        .header("Authorization", bearer(Role.MODERATOR, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
    }

    private static String startsWithImages() {
        return org.mockito.ArgumentMatchers.argThat(key -> key.startsWith("images/") && key.endsWith(".png"));
    }
}
