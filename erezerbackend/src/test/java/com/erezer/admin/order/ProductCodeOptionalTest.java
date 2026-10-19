package com.erezer.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erezer.admin.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The product code is optional; when it is missing nothing prints an empty "Code:". */
class ProductCodeOptionalTest extends IntegrationTest {

    @Test
    void ordersWithoutAProductCode() throws Exception {
        String admin = adminBearer();
        MvcResult created = mvc.perform(post("/api/orders").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethod":"CASH","deliveryCharge":60,
                                 "customer":{"name":"No Code","phone":"01711000666","address":"Mirpur-2, Dhaka"},
                                 "items":[{"productName":"Custom Hoodie","productCode":"  ","size":"L","unitPrice":1500,"quantity":1,"imageIds":[]},
                                          {"productName":"Cap","unitPrice":400,"quantity":1,"imageIds":[]}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        long id = ((Number) read(created, "$.id")).longValue();

        mvc.perform(get("/api/orders/" + id).header("Authorization", admin))
                .andExpect(jsonPath("$.items[0].productCode").doesNotExist())
                .andExpect(jsonPath("$.items[1].productCode").doesNotExist());

        byte[] pdf = mvc.perform(get("/api/orders/" + id + "/invoice.pdf").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains("Custom Hoodie", "Cap", "1,960.00").doesNotContain("Code:");
    }
}
