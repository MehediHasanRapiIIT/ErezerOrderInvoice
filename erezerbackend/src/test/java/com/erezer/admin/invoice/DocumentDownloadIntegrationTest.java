package com.erezer.admin.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.storage.StorageService;
import com.erezer.admin.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

class DocumentDownloadIntegrationTest extends IntegrationTest {

    @MockitoBean
    StorageService storage;

    @Test
    void invoicePdfIsServedInlineOrAsDownload() throws Exception {
        String admin = adminBearer();
        String number = createOrder(admin, "INV-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        long id = idOf(admin, number);

        MvcResult inline = mvc.perform(get("/api/orders/" + id + "/invoice.pdf").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"Invoice_" + number + ".pdf\""))
                .andReturn();
        PdfReader reader = new PdfReader(inline.getResponse().getContentAsByteArray());
        assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains(number, "Rafi", "Grand Total");
        reader.close();

        mvc.perform(get("/api/orders/" + id + "/invoice.pdf").param("download", "true").header("Authorization", admin))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Invoice_" + number + ".pdf\""));
    }

    @Test
    void excelExportContainsFilteredOrders() throws Exception {
        String admin = adminBearer();
        String phone = "0181" + String.format("%07d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 10_000_000));
        createOrder(admin, null, phone);
        createOrder(admin, null, phone);

        MvcResult result = mvc.perform(get("/api/orders/export.xlsx").param("q", phone).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"orders-")))
                .andReturn();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheet("Orders");
            assertThat(sheet.getLastRowNum()).isEqualTo(2); // header + 2 orders
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Order ID");
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo(phone);
            assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).contains("Pant (PN-1)");
            assertThat(sheet.getRow(1).getCell(13).getNumericCellValue()).isEqualTo(1260.0);
        }
    }

    @Test
    void documentsRequireOrderViewPermission() throws Exception {
        String admin = adminBearer();
        long id = idOf(admin, createOrder(admin, null));
        String noAccess = bearer(Role.MODERATOR, Permission.EXPENSE_VIEW);

        mvc.perform(get("/api/orders/" + id + "/invoice.pdf").header("Authorization", noAccess))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/export.xlsx").header("Authorization", noAccess))
                .andExpect(status().isForbidden());
    }

    private String createOrder(String admin, String number) throws Exception {
        return createOrder(admin, number, "01600000000");
    }

    private String createOrder(String admin, String number, String phone) throws Exception {
        String json = """
                {"orderNumber":%s,"paymentMethod":"BKASH","deliveryCharge":60,
                 "customer":{"name":"Rafi","phone":"%s","address":"Dhaka"},
                 "items":[{"productName":"Pant","productCode":"PN-1","size":"32","unitPrice":1200,"quantity":1,"imageIds":[]}]}
                """.formatted(number == null ? "null" : "\"" + number + "\"", phone);
        MvcResult created = mvc.perform(post("/api/orders").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = read(created, "$.id");
        MvcResult detail = mvc.perform(get("/api/orders/" + id).header("Authorization", admin)).andReturn();
        return read(detail, "$.orderNumber");
    }

    private long idOf(String admin, String number) throws Exception {
        MvcResult list = mvc.perform(get("/api/orders").param("q", number).header("Authorization", admin)).andReturn();
        Number id = read(list, "$.content[0].id");
        return id.longValue();
    }
}
