package com.erezer.admin.courier;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.courier.CourierDtos.BatchItemRequest;
import com.erezer.admin.courier.CourierDtos.BatchItemView;
import com.erezer.admin.courier.CourierDtos.BatchView;
import com.erezer.admin.courier.ParcelPlanner.Overrides;
import com.erezer.admin.order.Order;
import com.erezer.admin.order.OrderRepository;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk sending. The batch and its items are saved first, then booked in the background (4 orders at a time),
 * each through the same safe single-order path as the order page, so one bad order never affects the others and
 * results survive the browser being closed.
 *
 * <p>If Steadfast rejects the keys (401) or starts refusing requests (429), the rest of the batch is stopped:
 * Steadfast locks an account for an hour after 10 failed authentications in 5 minutes, so carrying on would make
 * things worse. The stopped orders are marked failed and can be sent again later.
 */
@Service
public class CourierBatchService {

    private static final Logger log = LoggerFactory.getLogger(CourierBatchService.class);
    static final int MAX_ITEMS = 500;
    private static final int PARALLELISM = 4;
    /** Errors that would fail every remaining order too, and risk an account lockout if repeated. */
    private static final java.util.Set<String> STOP_CODES = java.util.Set.of("COURIER_AUTH", "COURIER_RATE_LIMITED", "COURIER_DISABLED");

    private final CourierBatchRepository batches;
    private final CourierBatchItemRepository items;
    private final CourierService courierService;
    private final CourierSettingsService settings;
    private final OrderRepository orders;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Runs whole batches one after another; each batch fans out to its own small pool. */
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> new Thread(r, "courier-batch"));

    CourierBatchService(CourierBatchRepository batches, CourierBatchItemRepository items, CourierService courierService,
            CourierSettingsService settings, OrderRepository orders, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.batches = batches;
        this.items = items;
        this.courierService = courierService;
        this.settings = settings;
        this.orders = orders;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional
    public long start(List<BatchItemRequest> requests, long userId) {
        settings.requireCredentials();
        if (requests.size() > MAX_ITEMS) {
            throw ApiException.badRequest("BATCH_TOO_LARGE", "Send at most " + MAX_ITEMS + " orders at a time");
        }
        if (new HashSet<>(requests.stream().map(BatchItemRequest::orderId).toList()).size() != requests.size()) {
            throw ApiException.badRequest("DUPLICATE_ORDERS", "An order appears more than once in this batch");
        }
        CourierBatch batch = batches.save(new CourierBatch(CourierService.PROVIDER, requests.size(), userId, clock.instant()));
        for (int i = 0; i < requests.size(); i++) {
            BatchItemRequest r = requests.get(i);
            Order order = orders.findById(r.orderId()).orElseThrow(() -> ApiException.notFound("Order", r.orderId()));
            BigDecimal cod = r.codAmount() != null ? r.codAmount() : ParcelPlanner.defaultCod(order);
            items.save(new CourierBatchItem(batch.getId(), r.orderId(), cod,
                    r.deliveryType() != null ? r.deliveryType() : settings.availability().defaultDeliveryType(),
                    r.note(), r.itemDescription(), i));
        }
        long batchId = batch.getId();
        // Start only after the batch is committed, so the background thread can see it.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runner.submit(() -> run(batchId, userId));
            }
        });
        return batchId;
    }

    void run(long batchId, long userId) {
        List<CourierBatchItem> pending = items.findByBatchIdOrderByPosition(batchId).stream()
                .filter(i -> i.getStatus() == CourierBatchItem.Status.PENDING)
                .toList();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM, r -> new Thread(r, "courier-batch-item"));
        AtomicReference<String> abort = new AtomicReference<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (CourierBatchItem item : pending) {
                futures.add(pool.submit(() -> process(item, userId, abort)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ex) {
                    log.error("Batch {} item failed unexpectedly", batchId, ex);
                }
            }
        } finally {
            pool.shutdown();
            finish(batchId);
        }
    }

    private void process(CourierBatchItem item, long userId, AtomicReference<String> abort) {
        String failure = null;
        String tracking = null;
        String info = null;
        String stopped = abort.get();
        if (stopped != null) {
            failure = "Not sent: " + stopped;
        } else try {
            var result = courierService.send(item.getOrderId(), new Overrides(item.getCodAmount(), item.getDeliveryType(),
                    item.getNote(), item.getItemDescription()), userId);
            tracking = result.shipment().trackingCode();
            if ("CONFIRMING".equals(result.state())) {
                info = "Sent; Steadfast will confirm shortly";
            }
        } catch (ApiException ex) {
            failure = ex.getMessage();
            if (STOP_CODES.contains(ex.getCode()) && abort.compareAndSet(null, "the batch was stopped because " + ex.getMessage())) {
                log.warn("Batch stopped after {} on order {}", ex.getCode(), item.getOrderId());
            }
        } catch (RuntimeException ex) {
            log.error("Unexpected error sending order {}", item.getOrderId(), ex);
            failure = "Unexpected error: " + ex.getMessage();
        }
        String finalFailure = failure;
        String finalTracking = tracking;
        String finalInfo = info;
        tx.executeWithoutResult(s -> {
            CourierBatchItem fresh = items.findById(item.getId()).orElseThrow();
            if (finalFailure == null) {
                fresh.succeed(finalTracking, finalInfo);
            } else {
                fresh.fail(finalFailure);
            }
        });
    }

    private void finish(long batchId) {
        tx.executeWithoutResult(s -> {
            CourierBatch batch = batches.findById(batchId).orElseThrow();
            batch.complete((int) items.countByBatchIdAndStatus(batchId, CourierBatchItem.Status.SUCCESS),
                    (int) items.countByBatchIdAndStatus(batchId, CourierBatchItem.Status.FAILED), clock.instant());
        });
    }

    /** Batches left RUNNING by a restart: their unfinished items are marked failed so they can be resent. */
    @Transactional
    public void recoverInterrupted() {
        for (CourierBatch batch : batches.findAll()) {
            if (batch.getStatus() == CourierBatch.Status.RUNNING
                    && batch.getCreatedAt().isBefore(clock.instant().minus(Duration.ofMinutes(30)))) {
                items.findByBatchIdOrderByPosition(batch.getId()).stream()
                        .filter(i -> i.getStatus() == CourierBatchItem.Status.PENDING)
                        .forEach(i -> i.fail("Interrupted (the server restarted); check the order and send it again"));
                batch.complete((int) items.countByBatchIdAndStatus(batch.getId(), CourierBatchItem.Status.SUCCESS),
                        (int) items.countByBatchIdAndStatus(batch.getId(), CourierBatchItem.Status.FAILED), clock.instant());
            }
        }
    }

    @Transactional(readOnly = true)
    public BatchView get(long batchId) {
        CourierBatch batch = batches.findById(batchId).orElseThrow(() -> ApiException.notFound("Batch", batchId));
        List<CourierBatchItem> list = items.findByBatchIdOrderByPosition(batchId);
        Map<Long, Order> byId = orders.findAllById(list.stream().map(CourierBatchItem::getOrderId).toList()).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        List<BatchItemView> views = list.stream().map(i -> {
            Order o = byId.get(i.getOrderId());
            return new BatchItemView(i.getOrderId(), o == null ? "?" : o.getOrderNumber(), o == null ? null : o.getCustomerName(),
                    i.getStatus().name(), i.getMessage(), i.getTrackingCode(), i.getCodAmount());
        }).toList();
        int processed = (int) list.stream().filter(i -> i.getStatus() != CourierBatchItem.Status.PENDING).count();
        int succeeded = (int) list.stream().filter(i -> i.getStatus() == CourierBatchItem.Status.SUCCESS).count();
        return new BatchView(batch.getId(), batch.getStatus().name(), batch.getTotal(), succeeded, processed - succeeded,
                processed, batch.getCreatedAt(), batch.getFinishedAt(), views);
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
