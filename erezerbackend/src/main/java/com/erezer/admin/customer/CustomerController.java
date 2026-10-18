package com.erezer.admin.customer;

import com.erezer.admin.config.AppProperties;
import com.erezer.admin.customer.CustomerDtos.CustomerPage;
import com.erezer.admin.customer.CustomerDtos.CustomerRow;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
class CustomerController {

    private final CustomerQueryService queryService;
    private final CustomerExportService exportService;
    private final AppProperties appProperties;

    CustomerController(CustomerQueryService queryService, CustomerExportService exportService,
            AppProperties appProperties) {
        this.queryService = queryService;
        this.exportService = exportService;
        this.appProperties = appProperties;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    CustomerPage list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String segment,
            @RequestParam(defaultValue = "lastOrder") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.search(new CustomerQuery(q, segment, sort), Math.max(page, 0), Math.clamp(size, 1, 100));
    }

    /**
     * Recognises a returning customer while an order is being entered. Order staff may use it without the
     * Customers permission, but it only answers for one exact, complete phone number.
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'CUSTOMER_VIEW')")
    ResponseEntity<CustomerRow> lookup(@RequestParam String phone) {
        return queryService.findByPhone(phone).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Exporting the whole customer base is separately permissioned: it is personal data leaving the system. */
    @GetMapping("/export.xlsx")
    @PreAuthorize("hasAuthority('CUSTOMER_EXPORT')")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String segment,
            @RequestParam(defaultValue = "lastOrder") String sort) {
        byte[] workbook = exportService.export(new CustomerQuery(q, segment, sort));
        String fileName = "customers-" + LocalDate.now(appProperties.zoneId()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(workbook);
    }
}
