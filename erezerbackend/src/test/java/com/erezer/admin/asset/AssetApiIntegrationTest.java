package com.erezer.admin.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AssetApiIntegrationTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Dhaka"));

    @MockitoBean
    StorageService storage;

    private String admin;
    /** A fresh category per test keeps totals and lists isolated from other tests. */
    private long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        when(storage.presignedGetUrl(anyString())).thenReturn(URI.create("http://storage.test/signed").toURL());
        admin = adminBearer();
        categoryId = createCategory("Cat " + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void createWorksOutTotalAndStartsTheHistory() throws Exception {
        long id = createAsset("CCTV camera", 4, "2500", "Shop", null);

        mvc.perform(get("/api/assets/" + id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CCTV camera"))
                .andExpect(jsonPath("$.totalCost").value(10000.0))
                .andExpect(jsonPath("$.status").value("IN_USE"))
                .andExpect(jsonPath("$.repairTotal").value(0))
                .andExpect(jsonPath("$.events[0].kind").value("CREATED"))
                .andExpect(jsonPath("$.events[0].createdBy.name").value("Test ADMIN"));

        createAsset("Plastic chair", 6, "450", "Shop", null);
        list("categoryId", String.valueOf(categoryId))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.filteredValue").value(12700.0))
                .andExpect(jsonPath("$.filteredItems").value(10))
                .andExpect(jsonPath("$.summary.ownedValue").isNumber());
    }

    @Test
    void editLogsWhatChangedAndAMove() throws Exception {
        long id = createAsset("Table", 1, "8000", "Shop", null);
        mvc.perform(put("/api/assets/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Work table", 2, "8000", "Office", null, 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCost").value(16000.0))
                .andExpect(jsonPath("$.location").value("Office"))
                .andExpect(jsonPath("$.events[?(@.kind == 'MOVED')].description").value("Moved from Shop to Office"))
                .andExpect(jsonPath("$.events[?(@.kind == 'UPDATED')].description")
                        .value("Changed name (Table → Work table), quantity (1 → 2)"));

        // An old version is refused.
        mvc.perform(put("/api/assets/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Work table", 3, "8000", "Office", null, 0L)))
                .andExpect(status().isConflict());
    }

    @Test
    void soldAssetsLeaveTheOwnedListAndValue() throws Exception {
        long id = createAsset("Old printer", 1, "12000", null, null);

        setStatus(id, "{\"status\":\"SOLD\",\"date\":\"" + TODAY + "\"}").andExpect(status().isBadRequest());
        setStatus(id, "{\"status\":\"SOLD\",\"date\":\"" + TODAY + "\",\"salePrice\":3000,\"note\":\"to a neighbour\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.salePrice").value(3000.0))
                .andExpect(jsonPath("$.disposedOn").value(TODAY.toString()))
                .andExpect(jsonPath("$.events[0].description").value("In use → Sold for ৳3,000 · to a neighbour"));

        String cat = String.valueOf(categoryId);
        list("categoryId", cat).andExpect(jsonPath("$.totalElements").value(0));
        list("categoryId", cat, "status", "ALL").andExpect(jsonPath("$.totalElements").value(1));
        list("categoryId", cat, "status", "SOLD").andExpect(jsonPath("$.filteredValue").value(12000.0));

        // Back in use: the sale details are cleared.
        setStatus(id, "{\"status\":\"IN_USE\",\"date\":\"" + TODAY + "\"}")
                .andExpect(jsonPath("$.salePrice").doesNotExist())
                .andExpect(jsonPath("$.disposedOn").doesNotExist());
        setStatus(id, "{\"status\":\"IN_USE\",\"date\":\"" + TODAY + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void repairsAddUpAndOnlyRepairsCanBeRemoved() throws Exception {
        long id = createAsset("AC", 1, "55000", "Shop", null);
        repair(id, "Gas refill", "1500");
        MvcResult result = repair(id, "Service", "500").andExpect(jsonPath("$.repairTotal").value(2000.0)).andReturn();
        Number repairId = read(result, "$.events[0].id");
        Number createdId = read(result, "$.events[2].id");

        mvc.perform(delete("/api/assets/" + id + "/repairs/" + createdId).header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/assets/" + id + "/repairs/" + repairId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repairTotal").value(1500.0));
    }

    @Test
    void assetsNeverTouchExpensesOrTheDashboard() throws Exception {
        MvcResult before = mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)).andReturn();
        MvcResult expensesBefore = mvc.perform(get("/api/expenses").header("Authorization", admin)).andReturn();

        long id = createAsset("Rack", 3, "4000", "Warehouse", null);
        repair(id, "Welding", "700");

        MvcResult after = mvc.perform(get("/api/dashboard/summary").header("Authorization", admin)).andReturn();
        MvcResult expensesAfter = mvc.perform(get("/api/expenses").header("Authorization", admin)).andReturn();
        assertThat((Object) read(after, "$.current.expenses")).isEqualTo(read(before, "$.current.expenses"));
        assertThat((Object) read(after, "$.current.netProfit")).isEqualTo(read(before, "$.current.netProfit"));
        assertThat((Object) read(expensesAfter, "$.totalAmount")).isEqualTo(read(expensesBefore, "$.totalAmount"));
    }

    @Test
    void warrantyFilterAndValidation() throws Exception {
        createAsset("Laptop", 1, "60000", "Office", TODAY.plusDays(10));
        createAsset("Monitor", 1, "15000", "Office", TODAY.plusDays(200));
        String cat = String.valueOf(categoryId);
        list("categoryId", cat, "warranty", "SOON")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Laptop"));

        mvc.perform(post("/api/assets").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Future thing", 1, "10", null, null, null).replace(TODAY.toString(), TODAY.plusDays(2).toString())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assets").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Odd warranty", 1, "10", null, TODAY.minusDays(5), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void photoMustBeAnImageButTheReceiptMayBeAPdf() throws Exception {
        byte[] pdf = "%PDF-1.4\n%fake receipt\n".getBytes();
        MvcResult upload = mvc.perform(multipart("/api/files/receipts")
                        .file(new MockMultipartFile("file", "bill.pdf", "application/pdf", pdf))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andReturn();
        Number fileId = read(upload, "$.id");

        mvc.perform(post("/api/assets").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Camera", 1, "3000", null, null, null).replace("\"notes\"", "\"photoFileId\":" + fileId + ",\"notes\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assets").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Camera", 1, "3000", null, null, null).replace("\"notes\"", "\"receiptFileId\":" + fileId + ",\"notes\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt.fileName").value("bill.pdf"));
    }

    @Test
    void exportAndSoftDelete() throws Exception {
        long id = createAsset("Sewing machine", 2, "18000", "Workshop", null);

        MvcResult export = mvc.perform(get("/api/assets/export.xlsx").header("Authorization", admin)
                        .param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(export.getResponse().getContentAsByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Sewing machine");
            assertThat(sheet.getRow(2).getCell(5).getNumericCellValue()).isEqualTo(36000.0);
        }

        mvc.perform(delete("/api/assets/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/assets/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test
    void permissions() throws Exception {
        String viewer = bearer(Role.MODERATOR, Permission.ASSET_VIEW);
        mvc.perform(get("/api/assets").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/assets").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Chair", 1, "500", null, null, null)))
                .andExpect(status().isForbidden());

        String expensesOnly = bearer(Role.MODERATOR, Permission.EXPENSE_VIEW);
        mvc.perform(get("/api/assets").header("Authorization", expensesOnly)).andExpect(status().isForbidden());

        String manager = bearer(Role.MODERATOR, Permission.ASSET_VIEW, Permission.ASSET_MANAGE);
        MvcResult created = mvc.perform(post("/api/assets").header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Chair", 1, "500", null, null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = read(created, "$.id");
        mvc.perform(delete("/api/assets/" + id).header("Authorization", manager)).andExpect(status().isForbidden());
    }

    private long createCategory(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/asset-categories").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private long createAsset(String name, int quantity, String unitCost, String location, LocalDate warranty) throws Exception {
        MvcResult result = mvc.perform(post("/api/assets").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(name, quantity, unitCost, location, warranty, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private ResultActions setStatus(long id, String body) throws Exception {
        return mvc.perform(post("/api/assets/" + id + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions repair(long id, String description, String cost) throws Exception {
        return mvc.perform(post("/api/assets/" + id + "/repairs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + TODAY + "\",\"description\":\"" + description + "\",\"cost\":" + cost + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions list(String... params) throws Exception {
        var request = get("/api/assets").header("Authorization", admin);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isOk());
    }

    private String json(String name, int quantity, String unitCost, String location, LocalDate warranty, Long version) {
        return """
                {"name":"%s","categoryId":%d,"quantity":%d,"unitCost":%s,"purchaseDate":"%s","supplier":null,
                 "paymentMethod":"CASH","reference":null,"location":%s,"serialNumber":null,"warrantyUntil":%s,
                 "notes":null%s}
                """.formatted(name, categoryId, quantity, unitCost, TODAY,
                location == null ? "null" : "\"" + location + "\"",
                warranty == null ? "null" : "\"" + warranty + "\"",
                version == null ? "" : ",\"version\":" + version);
    }
}
