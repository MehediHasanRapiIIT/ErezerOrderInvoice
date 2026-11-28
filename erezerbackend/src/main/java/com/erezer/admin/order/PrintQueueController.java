package com.erezer.admin.order;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.order.PrintQueueService.Counts;
import com.erezer.admin.order.PrintQueueService.PrintOrder;
import com.erezer.admin.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/print-queue")
class PrintQueueController {

    private static final int MAX_PAGE_SIZE = 50;

    private final PrintQueueService queue;

    PrintQueueController(PrintQueueService queue) {
        this.queue = queue;
    }

    record PrintedRequest(boolean printed) {
    }

    /** @param sort {@code oldest} (default) or {@code newest} */
    @GetMapping
    @PreAuthorize("hasAuthority('PRINT_QUEUE_VIEW')")
    PageResponse<PrintOrder> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean printed,
            @RequestParam(defaultValue = "oldest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return queue.queue(q, printed, "newest".equalsIgnoreCase(sort), Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('PRINT_QUEUE_VIEW')")
    Counts counts(@RequestParam(required = false) String q) {
        return queue.counts(q);
    }

    @Notifies(NotificationAction.ORDER_PRINTED)
    @PutMapping("/{id}/printed")
    @PreAuthorize("hasAuthority('PRINT_QUEUE_MARK')")
    PrintOrder markPrinted(@PathVariable long id, @RequestBody PrintedRequest request) {
        if (!request.printed()) {
            Notifies.Context.as(NotificationAction.ORDER_UNPRINTED);
        }
        return queue.markPrinted(id, request.printed(), CurrentUser.id());
    }
}
