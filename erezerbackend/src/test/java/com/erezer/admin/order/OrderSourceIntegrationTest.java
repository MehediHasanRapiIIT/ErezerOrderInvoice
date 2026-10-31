package com.erezer.admin.order;

import static org.hamcrest.Matchers.hasItem;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Who accepted an order, where it came from, and the descriptions of its images. */
class OrderSourceIntegrationTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @MockitoBean
    StorageService storage;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/img").toURL());
        admin = adminBearer();
    }

    @Test
    void imagesKeepTheirOwnDescriptions() throws Exception {
        long first = image();
        long second = image();
        String body = order(null, "null", "[" + first + "," + second + "]",
                "{\"" + first + "\":\"  Front print  \",\"" + second + "\":\"  \"}");
        long id = id(save(body).andExpect(status().isCreated()));

        detail(id).andExpect(jsonPath("$.items[0].images[0].description").value("Front print"))
                .andExpect(jsonPath("$.items[0].images[1].description").doesNotExist());
    }

    @Test
    void aDescriptionHasALimit() throws Exception {
        long image = image();
        String tooLong = "x".repeat(501);
        save(order(null, "null", "[" + image + "]", "{\"" + image + "\":\"" + tooLong + "\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void whoAcceptedDefaultsToTheCreatorAndCanBeChosen() throws Exception {
        long staff = staff("Picker " + UUID.randomUUID());

        MvcResult mine = save(order(null, "\"FACEBOOK\"", "[]", "{}")).andExpect(status().isCreated()).andReturn();
        long mineId = ((Number) read(mine, "$.id")).longValue();
        detail(mineId).andExpect(jsonPath("$.platform").value("FACEBOOK"))
                .andExpect(jsonPath("$.acceptedBy.name").exists());

        long chosen = id(save(order(staff, "\"WHATSAPP\"", "[]", "{}")).andExpect(status().isCreated()));
        detail(chosen).andExpect(jsonPath("$.acceptedBy.id").value(staff))
                .andExpect(jsonPath("$.platform").value("WHATSAPP"));

        save(order(999_999L, "\"FACEBOOK\"", "[]", "{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STAFF"));
    }

    @Test
    void anEditCanChangeThemAndLeavingThemOutKeepsThem() throws Exception {
        long staff = staff("Editor " + UUID.randomUUID());
        long id = id(save(order(staff, "\"INSTAGRAM\"", "[]", "{}")).andExpect(status().isCreated()));

        Number version = read(detail(id).andReturn(), "$.version");
        edit(id, order(null, "null", "[]", "{}"), version).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.acceptedBy.id").value(staff)).andExpect(jsonPath("$.platform").value("INSTAGRAM"));

        version = read(detail(id).andReturn(), "$.version");
        edit(id, order(null, "\"WEBSITE\"", "[]", "{}"), version).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.platform").value("WEBSITE"));
    }

    @Test
    void otherLetsAPersonAndAPlatformBeTyped() throws Exception {
        String person = "Walk-in helper " + UUID.randomUUID();
        String platform = "Daraz " + UUID.randomUUID();
        long id = id(save(withOther(person, platform)).andExpect(status().isCreated()));

        detail(id).andExpect(jsonPath("$.acceptedByOther").value(person))
                .andExpect(jsonPath("$.acceptedBy").doesNotExist())
                .andExpect(jsonPath("$.platform").value("OTHER"))
                .andExpect(jsonPath("$.platformOther").value(platform));

        id(save(withOther(person.toUpperCase(), platform.toUpperCase())).andExpect(status().isCreated()));
        String today = LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
        mvc.perform(get("/api/dashboard/summary").param("from", today).param("to", today).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.sources.byUser[?(@.orders == 2)].name").value(hasItem(person.toUpperCase())))
                .andExpect(jsonPath("$.sources.byPlatform[?(@.orders == 2)].name").value(hasItem(platform.toUpperCase())));
    }

    @Test
    void otherPlatformNeedsTheTextButOlderOrdersCanStillBeEdited() throws Exception {
        save(order(null, "\"OTHER\"", "[]", "{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLATFORM_OTHER_REQUIRED"));

        long id = id(save(withOther("Helper", "Daraz")).andExpect(status().isCreated()));
        Number version = read(detail(id).andReturn(), "$.version");
        edit(id, order(null, "\"OTHER\"", "[]", "{}"), version).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.platformOther").value("Daraz")).andExpect(jsonPath("$.acceptedByOther").value("Helper"));

        version = read(detail(id).andReturn(), "$.version");
        long staff = staff("Chosen " + UUID.randomUUID());
        edit(id, order(staff, "\"FACEBOOK\"", "[]", "{}"), version).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.acceptedBy.id").value(staff))
                .andExpect(jsonPath("$.acceptedByOther").doesNotExist())
                .andExpect(jsonPath("$.platformOther").doesNotExist());
    }

    private static String withOther(String person, String platform) {
        return order(null, "\"OTHER\"", "[]", "{}").replace("\"acceptedBy\":null",
                "\"acceptedByOther\":\"" + person + "\",\"platformOther\":\"" + platform + "\"");
    }

    @Test
    void theStaffListNeedsOrderAccess() throws Exception {
        String name = "Listed " + UUID.randomUUID();
        staff(name);
        mvc.perform(get("/api/orders/staff").header(HttpHeaders.AUTHORIZATION, bearer(Role.MODERATOR, Permission.ORDER_CREATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem(name)));
        mvc.perform(get("/api/orders/staff").header(HttpHeaders.AUTHORIZATION, bearer(Role.MODERATOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theDashboardTotalsByPersonAndPlatformLeaveOutCancelledOrders() throws Exception {
        String name = "Seller " + UUID.randomUUID();
        long staff = staff(name);
        save(order(staff, "\"TIKTOK\"", "[]", "{}")).andExpect(status().isCreated());
        save(order(staff, "\"TIKTOK\"", "[]", "{}")).andExpect(status().isCreated());
        long cancelled = id(save(order(staff, "\"TIKTOK\"", "[]", "{}")).andExpect(status().isCreated()));
        mvc.perform(post("/api/orders/" + cancelled + "/status").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        String today = LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
        mvc.perform(get("/api/dashboard/summary").param("from", today).param("to", today).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.byUser[?(@.name == '" + name + "')].orders").value(hasItem(2)))
                .andExpect(jsonPath("$.sources.byUser[?(@.name == '" + name + "')].amount").value(hasItem(1920.0)))
                .andExpect(jsonPath("$.sources.byPlatform[?(@.name == 'TIKTOK')].orders").value(hasItem(2)));
    }

    private long staff(String name) throws Exception {
        MvcResult created = mvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin)
                        .content("{\"fullName\":\"" + name + "\",\"email\":\"s-" + UUID.randomUUID() + "@test.local\",\"role\":\"MODERATOR\","
                                + "\"password\":\"" + PASSWORD + "\",\"permissions\":[]}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(created, "$.id")).longValue();
    }

    private long image() throws Exception {
        MvcResult upload = mvc.perform(multipart("/api/files/images").file(new MockMultipartFile("files", "a.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andReturn();
        return ((Number) read(upload, "$[0].id")).longValue();
    }

    /** 450 × 2 + 60 delivery = 960. */
    private static String order(Long acceptedBy, String platform, String imageIds, String descriptions) {
        return """
                {"paymentMethod":"CASH","deliveryCharge":60,"acceptedBy":%s,"platform":%s,
                 "customer":{"name":"Source","phone":"01711000444","address":"Mirpur-2, Dhaka"},
                 "items":[{"productName":"T-Shirt","unitPrice":450,"quantity":2,"imageIds":%s,"imageDescriptions":%s}]}
                """.formatted(acceptedBy, platform, imageIds, descriptions);
    }

    private ResultActions save(String body) throws Exception {
        return mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions edit(long id, String body, Number version) throws Exception {
        return mvc.perform(put("/api/orders/" + id).header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                .content(body.replaceFirst("\\{", "{\"version\":" + version + ",")));
    }

    private ResultActions detail(long id) throws Exception {
        return mvc.perform(get("/api/orders/" + id).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private long id(ResultActions created) throws Exception {
        return ((Number) read(created.andReturn(), "$.id")).longValue();
    }
}
