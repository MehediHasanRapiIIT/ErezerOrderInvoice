package com.erezer.admin.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.erezer.admin.invoice.ImagePreparer.PreparedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class InvoicePdfRendererTest {

    private final ImagePreparer preparer = new ImagePreparer();
    private final InvoicePdfRenderer renderer = new InvoicePdfRenderer(new InvoiceFonts(),
            new CompanyProperties("EREZER", "Mirpur-2, Dhaka", "01708697257", "contact@erezer.com", "Thank you!"));

    @Test
    void firstPageIsTheInvoiceAndImagesFollowTwoProductSlotsPerPage() throws Exception {
        // T-shirt with 3 images -> 2 slots; pant with 1 image -> 1 slot; cap without images -> none.
        InvoiceData invoice = invoice(List.of(
                line(1, "Regular Fit T-Shirt", images(3, true)),
                line(2, "Cargo Pant", images(1, false)),
                line(3, "Cap", List.of())));

        byte[] pdf = renderer.render(invoice);
        writeForInspection(pdf);

        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isEqualTo(3); // invoice + ceil(3 slots / 2)

        PdfTextExtractor text = new PdfTextExtractor(reader);
        String page1 = text.getTextFromPage(1);
        assertThat(page1).contains("INVOICE", "EZ-583882", "BILL TO", "Mehedi Hasan", "Grand Total",
                "Regular Fit T-Shirt", "Cash on delivery");
        assertThat(page1).containsPattern("Page 1 of\s*3");
        assertThat(page1).contains("৳").contains("2,760.00");
        assertThat(page1).doesNotContain("Product Images");

        String page2 = text.getTextFromPage(2);
        assertThat(page2).contains("Product Images", "1. Regular Fit T-Shirt", "1. Regular Fit T-Shirt (continued)");
        String page3 = text.getTextFromPage(3);
        assertThat(page3).contains("2. Cargo Pant").doesNotContain("Cap");
        reader.close();
    }

    @Test
    void orderWithoutImagesIsASinglePage() throws Exception {
        byte[] pdf = renderer.render(invoice(List.of(line(1, "Cap", List.of()))));
        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        reader.close();
    }

    private List<PreparedImage> images(int count, boolean portrait) throws IOException {
        List<PreparedImage> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BufferedImage img = new BufferedImage(portrait ? 600 : 900, portrait ? 900 : 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(30 + 60 * i, 90, 200));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            result.add(preparer.prepare(out.toByteArray()).orElseThrow());
        }
        return result;
    }

    private static InvoiceData.Line line(int number, String name, List<PreparedImage> images) {
        return new InvoiceData.Line(number, name, "TS-100" + number, number == 1 ? "100% cotton" : null,
                "White", "L", new BigDecimal("900.00"), number, new BigDecimal(900 * number).setScale(2), images);
    }

    private static InvoiceData invoice(List<InvoiceData.Line> lines) {
        BigDecimal subtotal = lines.stream().map(InvoiceData.Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal delivery = new BigDecimal("60.00");
        BigDecimal discount = lines.size() > 1 ? new BigDecimal("2700.00") : BigDecimal.ZERO;
        return new InvoiceData("EZ-583882", ZonedDateTime.of(2026, 10, 9, 23, 1, 0, 0, ZoneId.of("Asia/Dhaka")),
                "Cash on delivery", "Mehedi Hasan", "01646065553", "m@example.com", "Mirpur-2, Dhaka", lines,
                subtotal, discount, delivery, subtotal.subtract(discount).add(delivery), "Pathao", "PT123");
    }

    /** Leaves the PDF in target/ so a human can eyeball the layout after `mvn test`. */
    private static void writeForInspection(byte[] pdf) throws IOException {
        Path out = Path.of("target", "sample-invoice.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);
    }
}
