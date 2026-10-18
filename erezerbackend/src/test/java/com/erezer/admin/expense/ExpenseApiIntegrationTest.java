package com.erezer.admin.expense;

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

class ExpenseApiIntegrationTest extends IntegrationTest {

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
    void createListAndTotalsPerCategory() throws Exception {
        long id = createExpense("Fabric roll", "1500.50", TODAY.minusDays(1), null);
        createExpense("Thread", "249.50", TODAY, null);

        mvc.perform(get("/api/expenses/" + id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Fabric roll"))
                .andExpect(jsonPath("$.amount").value(1500.50))
                .andExpect(jsonPath("$.category.id").value(categoryId))
                .andExpect(jsonPath("$.createdBy.name").value("Test ADMIN"));

        mvc.perform(get("/api/expenses").header("Authorization", admin).param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].description").value("Thread")) // newest date first
                .andExpect(jsonPath("$.totalAmount").value(1750.0))
                .andExpect(jsonPath("$.byCategory[0].categoryId").value(categoryId))
                .andExpect(jsonPath("$.byCategory[0].count").value(2));
    }

    @Test
    void filtersBySearchAmountAndDate() throws Exception {
        String ref = "BILL-" + UUID.randomUUID().toString().substring(0, 6);
        createExpense("Courier bill", "300", TODAY.minusDays(10), ref);
        createExpense("Packaging tape", "80", TODAY.minusDays(2), null);
        String cat = String.valueOf(categoryId);

        list("q", ref.toLowerCase(), "categoryId", cat).andExpect(jsonPath("$.totalElements").value(1));
        list("minAmount", "100", "categoryId", cat).andExpect(jsonPath("$.content[0].description").value("Courier bill"));
        list("from", TODAY.minusDays(3).toString(), "categoryId", cat)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalAmount").value(80.0));
        list("from", TODAY.toString(), "to", TODAY.minusDays(1).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void rejectsFutureDatesAndNonPositiveAmounts() throws Exception {
        mvc.perform(post("/api/expenses").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Advance", "100", TODAY.plusDays(1), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FUTURE_DATE"));

        mvc.perform(post("/api/expenses").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Nothing", "0", TODAY, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    void inactiveCategoryCannotTakeNewExpensesButKeepsExistingOnes() throws Exception {
        long id = createExpense("Old rent", "5000", TODAY, null);
        mvc.perform(put("/api/expense-categories/" + categoryId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Retired\",\"active\":false}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/expenses").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("New rent", "5000", TODAY, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INACTIVE_CATEGORY"));

        mvc.perform(put("/api/expenses/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Old rent (corrected)", "5100", TODAY, null, 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.name").value("Retired"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        long id = createExpense("Electricity", "1200", TODAY, null);
        mvc.perform(put("/api/expenses/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Electricity", "1250", TODAY, null, 7L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));
    }

    @Test
    void deletedExpenseDisappearsFromListAndTotals() throws Exception {
        long id = createExpense("Mistake", "999", TODAY, null);
        mvc.perform(delete("/api/expenses/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/expenses/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
        list("categoryId", String.valueOf(categoryId))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    void categoryNamesAreUniqueAndUsedCategoriesCannotBeDeleted() throws Exception {
        String name = "Dup " + UUID.randomUUID().toString().substring(0, 6);
        createCategory(name);
        mvc.perform(post("/api/expense-categories").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name.toUpperCase() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));

        createExpense("Uses category", "10", TODAY, null);
        mvc.perform(delete("/api/expense-categories/" + categoryId).header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        long unused = createCategory("Unused " + UUID.randomUUID().toString().substring(0, 6));
        mvc.perform(delete("/api/expense-categories/" + unused).header("Authorization", admin))
                .andExpect(status().isNoContent());
    }

    @Test
    void pdfReceiptCanBeAttachedButOtherFilesAreRejected() throws Exception {
        byte[] pdf = "%PDF-1.4\n%fake receipt\n".getBytes();
        MvcResult upload = mvc.perform(multipart("/api/files/receipts")
                        .file(new MockMultipartFile("file", "bill.pdf", "application/pdf", pdf))
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andReturn();
        Number fileId = read(upload, "$.id");

        MvcResult created = mvc.perform(post("/api/expenses").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Printer ink", "650", TODAY, null, null)
                                .replace("\"notes\"", "\"receiptFileId\":" + fileId + ",\"notes\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.receipt.fileName").value("bill.pdf"))
                .andExpect(jsonPath("$.receipt.url").value("http://storage.test/signed"))
                .andReturn();
        assertThat(((Number) read(created, "$.id")).longValue()).isPositive();

        mvc.perform(multipart("/api/files/receipts")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "MZ-not-a-pdf".getBytes()))
                        .header("Authorization", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE"));
    }

    @Test
    void exportContainsMatchingRowsAndATotal() throws Exception {
        createExpense("Rent", "8000", TODAY.minusDays(1), null);
        createExpense("Internet", "1500", TODAY, null);

        MvcResult result = mvc.perform(get("/api/expenses/export.xlsx").header("Authorization", admin)
                        .param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheet("Expenses");
            assertThat(sheet.getLastRowNum()).isEqualTo(3); // header, 2 rows, total
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Internet");
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("Total");
            assertThat(sheet.getRow(3).getCell(3).getNumericCellValue()).isEqualTo(9500.0);
        }
    }

    @Test
    void permissionsAreEnforced() throws Exception {
        String viewer = bearer(Role.MODERATOR, Permission.EXPENSE_VIEW);
        mvc.perform(get("/api/expenses").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/expenses").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                        .content(json("X", "1", TODAY, null, null)))
                .andExpect(status().isForbidden());

        String ordersOnly = bearer(Role.MODERATOR, Permission.ORDER_VIEW);
        mvc.perform(get("/api/expenses").header("Authorization", ordersOnly)).andExpect(status().isForbidden());
        mvc.perform(get("/api/expense-categories").header("Authorization", ordersOnly)).andExpect(status().isForbidden());
    }

    private long createCategory(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/expense-categories").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private long createExpense(String description, String amount, LocalDate date, String reference) throws Exception {
        MvcResult result = mvc.perform(post("/api/expenses").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(description, amount, date, reference, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private ResultActions list(String... params) throws Exception {
        var request = get("/api/expenses").header("Authorization", admin);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private String json(String description, String amount, LocalDate date, String reference, Long version) {
        return """
                {"expenseDate":"%s","categoryId":%d,"description":"%s","amount":%s,"paymentMethod":"CASH",
                 "reference":%s,"notes":null%s}
                """.formatted(date, categoryId, description, amount,
                reference == null ? "null" : "\"" + reference + "\"",
                version == null ? "" : ",\"version\":" + version);
    }
}
