package com.erezer.admin.invoice;

import com.erezer.admin.invoice.InvoiceService.Invoice;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvoiceController {

    private final InvoiceService invoiceService;

    InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /**
     * @param download {@code true} to force a file download; otherwise the browser shows it inline
     */
    @GetMapping("/api/orders/{id}/invoice.pdf")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    ResponseEntity<byte[]> invoice(@PathVariable long id, @RequestParam(defaultValue = "false") boolean download) {
        Invoice invoice = invoiceService.render(id);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(invoice.fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore())
                .body(invoice.pdf());
    }
}
