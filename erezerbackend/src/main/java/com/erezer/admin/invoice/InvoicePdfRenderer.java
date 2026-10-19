package com.erezer.admin.invoice;

import com.erezer.admin.invoice.ImagePreparer.PreparedImage;
import com.erezer.admin.invoice.InvoiceData.Line;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders the A4 invoice.
 *
 * <ul>
 *   <li>Page 1 (continuing if there are many items): header band, bill-to, items table and totals.</li>
 *   <li>Page 2 onwards: product images, two product slots per page. Each slot shows the product's heading
 *       and up to two images side by side; a product with more images continues in the next slot.</li>
 * </ul>
 * Every page carries a faint logo watermark and a "Page x of y" footer.
 */
@Component
class InvoicePdfRenderer {

    private static final Color INK = new Color(12, 10, 9);
    private static final Color MUTED = new Color(120, 113, 108);
    private static final Color SOFT = new Color(87, 83, 78);
    private static final Color LINE = new Color(231, 229, 228);
    private static final Color STRIPE = new Color(250, 249, 248);
    private static final Color BAND_KEY = new Color(168, 162, 158);
    private static final Color BAND_RULE = new Color(42, 39, 36);
    private static final Color DISCOUNT = new Color(185, 28, 28);

    static final String BRAND_TITLE = "Erezer - Erase the Ordinary";
    static final String TAGLINE = "Erase the Ordinary";

    private static final float MARGIN = 36;
    /** Page 1 header: accent strip, wordmark + tagline, INVOICE title and the dark date band. */
    private static final float HEADER_HEIGHT = 128;
    private static final float ACCENT = 6;
    private static final float FOOTER_SPACE = 48;
    private static final float SLOT_GAP = 18;
    private static final float IMAGE_GAP = 14;
    private static final int IMAGES_PER_SLOT = 2;
    private static final int SLOTS_PER_PAGE = 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final InvoiceFonts fonts;
    private final CompanyProperties company;
    private final byte[] logo;
    private final byte[] wordmark;

    InvoicePdfRenderer(InvoiceFonts fonts, CompanyProperties company) {
        this.fonts = fonts;
        this.company = company;
        this.logo = readClasspath("branding/logo.png");
        this.wordmark = readClasspath("branding/logo-wordmark.png");
    }

    byte[] render(InvoiceData invoice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, MARGIN, MARGIN, HEADER_HEIGHT + 22, FOOTER_SPACE);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setPageEvent(new PageDecorations(invoice));
        document.addTitle(BRAND_TITLE + " · Invoice " + invoice.orderNumber());
        document.addSubject("Invoice " + invoice.orderNumber());
        document.addAuthor(company.name());
        document.open();
        // Pages after the first have no header band.
        document.setMargins(MARGIN, MARGIN, MARGIN + ACCENT, FOOTER_SPACE);

        document.add(billingBlock(invoice));
        if (invoice.exchange() != null) {
            document.add(sectionLabel("NEW ITEMS (SENT TO THE CUSTOMER)"));
        }
        document.add(itemsTable(invoice));
        if (invoice.exchange() != null) {
            document.add(sectionLabel("RETURNED ITEMS (COLLECTED FROM THE CUSTOMER)"));
            document.add(returnedTable(invoice.exchange()));
            document.add(exchangeTotals(invoice));
        } else {
            document.add(totalsBlock(invoice));
        }
        if (invoice.courier() != null) {
            Paragraph shipment = new Paragraph(fonts.phrase(
                    "Shipped via " + invoice.courier() + " · Tracking " + invoice.trackingNumber(), 9, false, MUTED));
            shipment.setSpacingBefore(10);
            document.add(shipment);
        }
        if (company.invoiceFooter() != null && !company.invoiceFooter().isBlank()) {
            Paragraph thanks = new Paragraph(fonts.phrase(company.invoiceFooter(), 10, true, INK));
            thanks.setAlignment(Element.ALIGN_CENTER);
            thanks.setSpacingBefore(28);
            document.add(thanks);
        }

        renderImagePages(document, writer, invoice);
        document.close();
        return out.toByteArray();
    }

    // ---- Page 1 ----------------------------------------------------------------------------------

    private PdfPTable billingBlock(InvoiceData invoice) {
        PdfPTable table = new PdfPTable(new float[] {50, 50});
        table.setWidthPercentage(100);
        table.setSpacingAfter(20);

        PdfPCell billedTo = party("BILLED TO");
        billedTo.addElement(new Paragraph(fonts.phrase(invoice.customerName(), 12, true, INK)));
        billedTo.addElement(new Paragraph(fonts.phrase(invoice.customerPhone(), 9.5f, false, SOFT)));
        if (invoice.customerEmail() != null) {
            billedTo.addElement(new Paragraph(fonts.phrase(invoice.customerEmail(), 9.5f, false, SOFT)));
        }
        table.addCell(billedTo);

        PdfPCell shipTo = party("SHIP TO");
        shipTo.addElement(new Paragraph(fonts.phrase(invoice.customerAddress(), 9.5f, false, SOFT)));
        table.addCell(shipTo);
        return table;
    }

    /** A column with a small caps label underlined by a hairline, as on the shop's invoice. */
    private PdfPCell party(String title) {
        PdfPCell cell = borderless();
        cell.setPaddingRight(18);
        PdfPTable heading = new PdfPTable(1);
        heading.setWidthPercentage(100);
        PdfPCell label = borderless(spaced(title, 8, false, MUTED, 1.5f));
        label.setBorder(Rectangle.BOTTOM);
        label.setBorderColor(LINE);
        label.setPaddingBottom(5);
        heading.addCell(label);
        heading.setSpacingAfter(6);
        cell.addElement(heading);
        return cell;
    }

    private PdfPTable itemsTable(InvoiceData invoice) {
        PdfPTable table = new PdfPTable(new float[] {6, 50, 8, 18, 18});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String heading : List.of("#", "ITEM DESCRIPTION", "QTY", "UNIT PRICE", "AMOUNT")) {
            PdfPCell cell = new PdfPCell(fonts.phrase(heading, 8.5f, true, Color.WHITE));
            cell.setBackgroundColor(Color.BLACK);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(7);
            cell.setHorizontalAlignment(heading.equals("ITEM DESCRIPTION") ? Element.ALIGN_LEFT
                    : heading.equals("QTY") || heading.equals("#") ? Element.ALIGN_CENTER : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }

        for (Line line : invoice.lines()) {
            Color background = line.number() % 2 == 0 ? STRIPE : Color.WHITE;
            table.addCell(itemCell(fonts.phrase(String.valueOf(line.number()), 9, false, MUTED), Element.ALIGN_CENTER, background));

            PdfPCell description = itemCell(null, Element.ALIGN_LEFT, background);
            description.addElement(new Paragraph(fonts.phrase(line.productName(), 10, true, INK)));
            String details = details(line);
            description.addElement(new Paragraph(fonts.phrase(details, 8.5f, false, MUTED)));
            if (line.description() != null) {
                description.addElement(new Paragraph(fonts.phrase(line.description(), 8.5f, false, MUTED)));
            }
            table.addCell(description);

            table.addCell(itemCell(fonts.phrase(String.valueOf(line.quantity()), 9.5f, false, INK), Element.ALIGN_CENTER, background));
            table.addCell(itemCell(fonts.phrase(money(line.unitPrice()), 9.5f, false, INK), Element.ALIGN_RIGHT, background));
            table.addCell(itemCell(fonts.phrase(money(line.lineTotal()), 9.5f, true, INK), Element.ALIGN_RIGHT, background));
        }
        return table;
    }

    private PdfPCell itemCell(Phrase content, int align, Color background) {
        PdfPCell cell = content == null ? new PdfPCell() : new PdfPCell(content);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(background);
        cell.setPadding(7);
        cell.setPaddingTop(content == null ? 3 : 8);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private PdfPTable totalsBlock(InvoiceData invoice) {
        PdfPTable table = new PdfPTable(new float[] {55, 45});
        table.setWidthPercentage(45);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(12);
        table.setKeepTogether(true);
        totalRow(table, "Subtotal", money(invoice.subtotal()), false, INK);
        if (invoice.discount().signum() > 0) {
            totalRow(table, "Discount", "− " + money(invoice.discount()), false, DISCOUNT);
        }
        totalRow(table, "Delivery charge", money(invoice.deliveryCharge()), false, INK);
        totalRow(table, "Grand Total", money(invoice.total()), true, INK);
        if (invoice.advance() != null && invoice.advance().signum() > 0) {
            totalRow(table, "Advance paid", "− " + money(invoice.advance()), false, INK);
            totalRow(table, "Due", money(invoice.total().subtract(invoice.advance())), true, INK);
        }
        return table;
    }

    private Paragraph sectionLabel(String text) {
        Paragraph p = new Paragraph(spaced(text, 8, true, MUTED, 1.2f));
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        return p;
    }

    private PdfPTable returnedTable(InvoiceData.Exchange exchange) {
        PdfPTable table = new PdfPTable(new float[] {64, 12, 24});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String heading : List.of("ITEM", "QTY", "CREDIT")) {
            PdfPCell cell = new PdfPCell(fonts.phrase(heading, 8.5f, true, INK));
            cell.setBackgroundColor(STRIPE);
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(LINE);
            cell.setPadding(7);
            cell.setHorizontalAlignment(heading.equals("ITEM") ? Element.ALIGN_LEFT
                    : heading.equals("QTY") ? Element.ALIGN_CENTER : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
        for (InvoiceData.Returned r : exchange.returned()) {
            PdfPCell item = itemCell(null, Element.ALIGN_LEFT, Color.WHITE);
            item.addElement(new Paragraph(fonts.phrase(r.description(), 9.5f, true, INK)));
            if (r.productCode() != null) {
                item.addElement(new Paragraph(fonts.phrase("Code: " + r.productCode(), 8.5f, false, MUTED)));
            }
            table.addCell(item);
            table.addCell(itemCell(fonts.phrase(String.valueOf(r.quantity()), 9.5f, false, INK), Element.ALIGN_CENTER, Color.WHITE));
            table.addCell(itemCell(fonts.phrase(money(r.credit()), 9.5f, false, INK), Element.ALIGN_RIGHT, Color.WHITE));
        }
        return table;
    }

    /** New items − returned credit = price difference; delivery by whoever pays it; what the rider collects. */
    private PdfPTable exchangeTotals(InvoiceData invoice) {
        InvoiceData.Exchange ex = invoice.exchange();
        PdfPTable table = new PdfPTable(new float[] {58, 42});
        table.setWidthPercentage(52);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(12);
        table.setKeepTogether(true);
        totalRow(table, "New items", money(invoice.subtotal()), false, INK);
        totalRow(table, "Returned items credit", "− " + money(ex.credit()), false, INK);
        BigDecimal difference = ex.priceDifference();
        if (difference.signum() >= 0) {
            totalRow(table, "Price difference", money(difference), false, INK);
        } else if (ex.refund()) {
            totalRow(table, "Refund to customer", money(difference.negate()), false, DISCOUNT);
        } else {
            totalRow(table, "Price difference (no refund)", money(difference.negate()), false, MUTED);
        }
        if (ex.paidByErezer()) {
            totalRow(table, "Exchange delivery", "Paid by Erezer", false, MUTED);
        } else {
            totalRow(table, "Exchange delivery", money(invoice.deliveryCharge()), false, INK);
        }
        totalRow(table, "Amount to collect", money(invoice.total()), true, INK);
        return table;
    }

    private void totalRow(PdfPTable table, String name, String value, boolean grand, Color valueColor) {
        PdfPCell key = borderless(fonts.phrase(name, grand ? 11 : 9.5f, grand, grand ? INK : MUTED));
        PdfPCell val = borderless(fonts.phrase(value, grand ? 12 : 9.5f, grand, valueColor));
        val.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell cell : List.of(key, val)) {
            cell.setPaddingTop(grand ? 8 : 3);
            cell.setPaddingBottom(grand ? 8 : 3);
            if (grand) {
                cell.setBorder(Rectangle.TOP);
                cell.setBorderColor(INK);
                cell.setBorderWidth(1.2f);
                cell.setBackgroundColor(STRIPE);
            }
            table.addCell(cell);
        }
    }

    // ---- Image pages -----------------------------------------------------------------------------

    /** One slot = one product heading + up to two images; a product with more images spans several slots. */
    private record Slot(Line line, List<PreparedImage> images, boolean continued) {
    }

    private void renderImagePages(Document document, PdfWriter writer, InvoiceData invoice) {
        List<Slot> slots = new ArrayList<>();
        for (Line line : invoice.lines()) {
            for (int i = 0; i < line.images().size(); i += IMAGES_PER_SLOT) {
                slots.add(new Slot(line, line.images().subList(i, Math.min(i + IMAGES_PER_SLOT, line.images().size())), i > 0));
            }
        }
        if (slots.isEmpty()) {
            return;
        }

        Rectangle page = document.getPageSize();
        float left = MARGIN;
        float right = page.getWidth() - MARGIN;
        float titleTop = page.getHeight() - MARGIN;
        float contentTop = titleTop - 30;
        float contentBottom = FOOTER_SPACE + 4;
        float slotHeight = (contentTop - contentBottom - SLOT_GAP * (SLOTS_PER_PAGE - 1)) / SLOTS_PER_PAGE;

        for (int i = 0; i < slots.size(); i++) {
            int position = i % SLOTS_PER_PAGE;
            if (position == 0) {
                document.newPage();
                writer.setPageEmpty(false); // content below is drawn absolutely
                PdfContentByte canvas = writer.getDirectContent();
                ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                        fonts.phrase("Product Images", 14, true, INK), left, titleTop - 14, 0);
                ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT,
                        fonts.phrase("Order " + invoice.orderNumber(), 9, false, MUTED), right, titleTop - 12, 0);
                canvas.setColorStroke(LINE);
                canvas.setLineWidth(0.8f);
                canvas.moveTo(left, titleTop - 22);
                canvas.lineTo(right, titleTop - 22);
                canvas.stroke();
            }
            float slotTop = contentTop - position * (slotHeight + SLOT_GAP);
            drawSlot(writer.getDirectContent(), slots.get(i), left, right, slotTop, slotTop - slotHeight);
        }
    }

    private void drawSlot(PdfContentByte canvas, Slot slot, float left, float right, float top, float bottom) {
        Line line = slot.line();
        String heading = line.number() + ". " + line.productName() + (slot.continued() ? " (continued)" : "");
        ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, fonts.phrase(heading, 11.5f, true, INK), left, top - 12, 0);
        String details = details(line);
        ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, fonts.phrase(details, 8.5f, false, MUTED), left, top - 25, 0);

        float boxTop = top - 34;
        float boxHeight = boxTop - bottom;
        float boxWidth = (right - left - IMAGE_GAP) / IMAGES_PER_SLOT;
        int count = slot.images().size();
        // A single image is centred across the slot instead of sitting in the left column.
        float firstLeft = count == 1 ? left + (right - left - boxWidth) / 2 : left;

        for (int i = 0; i < count; i++) {
            float boxLeft = firstLeft + i * (boxWidth + IMAGE_GAP);
            canvas.setColorStroke(LINE);
            canvas.setLineWidth(0.6f);
            canvas.roundRectangle(boxLeft, bottom, boxWidth, boxHeight, 6);
            canvas.stroke();
            try {
                Image image = Image.getInstance(slot.images().get(i).jpeg());
                float pad = 6;
                image.scaleToFit(boxWidth - 2 * pad, boxHeight - 2 * pad);
                float x = boxLeft + (boxWidth - image.getScaledWidth()) / 2;
                float y = bottom + (boxHeight - image.getScaledHeight()) / 2;
                image.setAbsolutePosition(x, y);
                canvas.addImage(image);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    // ---- Shared decorations ----------------------------------------------------------------------

    /** Draws the first-page header band, watermark and "Page x of y" footer. */
    private final class PageDecorations extends PdfPageEventHelper {

        private final InvoiceData invoice;
        /** One instance per document, so the PDF embeds the logo once however many pages use it. */
        private final Image logoImage;
        private final Image wordmarkImage;
        private PdfTemplate totalPages;

        PageDecorations(InvoiceData invoice) {
            this.invoice = invoice;
            try {
                this.logoImage = Image.getInstance(logo);
                this.wordmarkImage = Image.getInstance(wordmark);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPages = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onStartPage(PdfWriter writer, Document document) {
            // Only page 1 is decorated here. Anything drawn in onStartPage marks the page as used, so the
            // watermark goes in onEndPage, or closing the document would emit a trailing blank page.
            if (writer.getPageNumber() == 1) {
                drawHeader(writer.getDirectContent(), document.getPageSize());
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            drawWatermark(writer, page);
            drawAccent(canvas, page);
            float y = 26;
            canvas.setColorStroke(LINE);
            canvas.setLineWidth(0.6f);
            canvas.moveTo(MARGIN, y + 12);
            canvas.lineTo(page.getWidth() - MARGIN, y + 12);
            canvas.stroke();

            String contact = String.join("  ·  ", nonBlank(company.name(), company.address(), company.phone(), company.email()));
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, fonts.phrase(contact, 8, false, MUTED), MARGIN, y, 0);

            String label = "Page " + writer.getPageNumber() + " of ";
            float right = page.getWidth() - MARGIN - 18;
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, fonts.phrase(label, 8, false, MUTED), right, y, 0);
            canvas.addTemplate(totalPages, right + 2, y); // alignment trims the label's trailing space
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            // At close the writer has already advanced to the next (never written) page.
            int pages = writer.getPageNumber() - 1;
            ColumnText.showTextAligned(totalPages, Element.ALIGN_LEFT,
                    fonts.phrase(String.valueOf(pages), 8, false, MUTED), 2, 0, 0);
        }

        private void drawHeader(PdfContentByte canvas, Rectangle page) {
            float top = page.getHeight();
            float left = MARGIN;
            float right = page.getWidth() - MARGIN;

            // Wordmark (865 x 96 px) with the tagline under it.
            float markHeight = 20;
            float markWidth = markHeight * wordmarkImage.getWidth() / wordmarkImage.getHeight();
            canvas.addImage(wordmarkImage, markWidth, 0, 0, markHeight, left, top - 30 - markHeight);
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, italic(TAGLINE, 9.5f, SOFT), left, top - 64, 0);

            String title = invoice.exchange() == null ? "INVOICE" : "EXCHANGE";
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, spaced(title, 24, true, INK, 4),
                    right, top - 48, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, fonts.phrase(invoice.orderNumber(), 10, false, SOFT),
                    right, top - 63, 0);

            // Dark band: accepted date · order date · payment.
            float bandTop = top - 80;
            float bandHeight = 44;
            canvas.saveState();
            canvas.setColorFill(INK);
            canvas.rectangle(left, bandTop - bandHeight, right - left, bandHeight);
            canvas.fill();
            float[] widths = {0.37f, 0.37f, 0.26f};
            String[][] cells = {
                    {"ACCEPTED DATE", invoice.acceptedAt() == null ? "Not accepted yet" : DATE.format(invoice.acceptedAt())},
                    {"ORDER DATE", DATE.format(invoice.orderedAt())},
                    invoice.exchange() == null
                            ? new String[] {"PAYMENT", invoice.paymentMethod().toUpperCase(Locale.ENGLISH)}
                            : new String[] {"EXCHANGE OF", invoice.exchange().ofOrderNumber()}};
            float x = left;
            canvas.setColorStroke(BAND_RULE);
            canvas.setLineWidth(0.8f);
            for (int i = 0; i < cells.length; i++) {
                float w = (right - left) * widths[i];
                if (i > 0) {
                    canvas.moveTo(x, bandTop);
                    canvas.lineTo(x, bandTop - bandHeight);
                    canvas.stroke();
                }
                ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, spaced(cells[i][0], 7, false, BAND_KEY, 1.2f),
                        x + 14, bandTop - 16, 0);
                ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, fonts.phrase(cells[i][1], 10.5f, true, Color.WHITE),
                        x + 14, bandTop - 32, 0);
                x += w;
            }
            canvas.restoreState();
        }

        private void drawAccent(PdfContentByte canvas, Rectangle page) {
            canvas.saveState();
            canvas.setColorFill(INK);
            canvas.rectangle(0, page.getHeight() - ACCENT, page.getWidth(), ACCENT);
            canvas.fill();
            canvas.restoreState();
        }

        private void drawWatermark(PdfWriter writer, Rectangle page) {
            PdfContentByte under = writer.getDirectContentUnder();
            under.saveState();
            PdfGState faint = new PdfGState();
            faint.setFillOpacity(0.05f);
            under.setGState(faint);
            float size = 300; // the logo is square
            under.addImage(logoImage, size, 0, 0, size, (page.getWidth() - size) / 2, (page.getHeight() - size) / 2);
            under.restoreState();
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** Letter-spaced text, e.g. "I N V O I C E". */
    private Phrase spaced(String text, float size, boolean bold, Color color, float spacing) {
        Phrase phrase = fonts.phrase(text, size, bold, color);
        for (Object chunk : phrase.getChunks()) {
            ((org.openpdf.text.Chunk) chunk).setCharacterSpacing(spacing);
        }
        return phrase;
    }

    /** Slanted text (the embedded Noto Sans has no italic face, so the glyphs are skewed). */
    private Phrase italic(String text, float size, Color color) {
        Phrase phrase = fonts.phrase(text, size, false, color);
        for (Object chunk : phrase.getChunks()) {
            ((org.openpdf.text.Chunk) chunk).setSkew(0, 12).setCharacterSpacing(0.6f);
        }
        return phrase;
    }

    private static PdfPCell borderless() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell borderless(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    /** "Code: TS-1  ·  Black / M", leaving out whatever the item does not have. */
    private static String details(Line line) {
        return String.join("  ·  ", nonBlank(line.productCode() == null ? null : "Code: " + line.productCode(), line.variant()));
    }

    static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "৳ " + format.format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                result.add(v);
            }
        }
        return result;
    }

    private static byte[] readClasspath(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing resource " + path, ex);
        }
    }
}
