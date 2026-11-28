package com.erezer.admin.shop;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.order.OrderPlatform;
import com.erezer.admin.order.OrderService;
import com.erezer.admin.order.dto.OrderDtos.OrderItemRequest;
import com.erezer.admin.order.dto.OrderDtos.OrderRequest;
import com.erezer.admin.shop.ShopDtos.Outcome;
import com.erezer.admin.shop.ShopDtos.ShopItem;
import com.erezer.admin.shop.ShopDtos.ShopOrder;
import com.erezer.admin.shop.ShopDtos.ShopOrderResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Turns an order from the Erezer shop into an order here, as if typed in Add Order: platform Website, taken by
 * "Erezer website", dated when the customer ordered. Its products aren't stock variants, so stock doesn't move.
 * Photos that can't be kept are left out and counted; they never stop the order.
 */
@Service
public class ShopOrderService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderService.class);

    private final OrderService orders;
    private final FileService files;
    private final ShopConnectionService connection;
    private final JdbcClient jdbc;

    ShopOrderService(OrderService orders, FileService files, ShopConnectionService connection, JdbcClient jdbc) {
        this.orders = orders;
        this.files = files;
        this.connection = connection;
        this.jdbc = jdbc;
    }

    /** @param photos each product line's photos, by its position in {@code order.items()} */
    public ShopOrderResult receive(ShopOrder order, Map<Integer, List<MultipartFile>> photos) {
        long userId = connection.systemUserId();
        // The order and its photos are the "Erezer website" account's own, as when staff add an order:
        // only the uploader may attach a photo, and the order's history names who made it.
        var before = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("shop-connection").header("alg", "none")
                .subject(Long.toString(userId)).build(), List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return receiveAs(order, photos, userId);
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    private ShopOrderResult receiveAs(ShopOrder order, Map<Integer, List<MultipartFile>> photos, long userId) {
        String number = order.orderNumber().trim();
        Optional<Long> existing = findByNumber(number);
        if (existing.isPresent()) {
            return new ShopOrderResult(number, Outcome.EXISTS, existing.get(), "Already in the order list", 0);
        }
        int missing = 0;
        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 0; i < order.items().size(); i++) {
            ShopItem item = order.items().get(i);
            List<MultipartFile> itemPhotos = photos.getOrDefault(i, List.of());
            List<Long> imageIds = new ArrayList<>();
            for (MultipartFile photo : itemPhotos.stream().limit(FileService.MAX_FILES_PER_UPLOAD).toList()) {
                try {
                    StoredFile stored = files.uploadImages(List.of(photo), userId).getFirst();
                    imageIds.add(stored.getId());
                } catch (ApiException e) {
                    missing++;
                    log.info("Shop order {}: photo {} left out ({})", number, photo.getOriginalFilename(), e.getMessage());
                }
            }
            missing += Math.max(0, itemPhotos.size() - FileService.MAX_FILES_PER_UPLOAD);
            Map<Long, String> descriptions = new LinkedHashMap<>();
            if (item.imageDescription() != null && !item.imageDescription().isBlank()) {
                imageIds.forEach(id -> descriptions.put(id, item.imageDescription().trim()));
            }
            items.add(new OrderItemRequest(item.productName().trim(), item.productCode(), item.description(), item.color(),
                    item.size(), item.unitPrice(), item.quantity(), imageIds, null, null, descriptions));
        }

        OrderRequest request = new OrderRequest(number, order.paymentMethod(), order.customer(), order.deliveryCharge(),
                order.discount(), items, null, order.advance(), order.orderedAt(), null, OrderPlatform.WEBSITE,
                ShopConnectionService.SYSTEM_NAME, null, false);
        try {
            long id = orders.create(request, userId, true);
            return new ShopOrderResult(number, Outcome.CREATED, id, null, missing);
        } catch (ApiException e) {
            // Sent twice at the same moment: the other request made it.
            Optional<Long> made = findByNumber(number);
            if (made.isPresent()) {
                return new ShopOrderResult(number, Outcome.EXISTS, made.get(), "Already in the order list", 0);
            }
            return new ShopOrderResult(number, Outcome.FAILED, null, e.getMessage(), missing);
        }
    }

    private Optional<Long> findByNumber(String number) {
        return jdbc.sql("select id from orders where lower(order_number) = lower(:number)").param("number", number)
                .query(Long.class).optional();
    }
}
