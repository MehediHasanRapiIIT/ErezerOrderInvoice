package com.erezer.admin.invoice;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.openpdf.text.Font;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.FontSelector;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Embedded Noto fonts. Latin text uses Noto Sans; characters it lacks (the taka sign ৳ and Bangla
 * letters) fall back to Noto Sans Bengali, glyph by glyph.
 *
 * <p>Limitation: OpenPDF does not shape complex scripts, so Bangla conjuncts in names or addresses
 * may print as separate letters. Latin text and the ৳ sign render correctly.
 */
@Component
class InvoiceFonts {

    private final BaseFont latin;
    private final BaseFont latinBold;
    private final BaseFont bengali;
    private final BaseFont bengaliBold;

    InvoiceFonts() {
        this.latin = load("fonts/NotoSans-Regular.ttf");
        this.latinBold = load("fonts/NotoSans-Bold.ttf");
        this.bengali = load("fonts/NotoSansBengali-Regular.ttf");
        this.bengaliBold = load("fonts/NotoSansBengali-Bold.ttf");
    }

    Phrase phrase(String text, float size, boolean bold, Color color) {
        FontSelector selector = new FontSelector();
        selector.addFont(new Font(bold ? latinBold : latin, size, Font.NORMAL, color));
        selector.addFont(new Font(bold ? bengaliBold : bengali, size, Font.NORMAL, color));
        return selector.process(text == null ? "" : text);
    }

    BaseFont latin() {
        return latin;
    }

    private static BaseFont load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load font " + path, ex);
        }
    }
}
