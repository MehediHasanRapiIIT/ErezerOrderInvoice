package com.erezer.admin.shop;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.shop.ShopDtos.ConnectionView;
import com.erezer.admin.shop.ShopDtos.NewKey;
import com.erezer.admin.shop.ShopDtos.ShopOrder;
import com.erezer.admin.shop.ShopDtos.ShopOrderResult;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Orders from the Erezer shop, and the key it sends them with.
 *
 * <p>{@code POST /api/integrations/shop/orders} takes one order: a multipart request with the order as JSON in the
 * part {@code order} and each product line's photos as files named {@code item-0}, {@code item-1}… It carries the
 * key in {@code X-Shop-Key}. The answer says whether the order was added, was already here, or why not.
 */
@RestController
class ShopController {

    private static final Pattern ITEM_PART = Pattern.compile("^item-([0-9]{1,2})$");

    private final ShopConnectionService connection;
    private final ShopOrderService orders;

    ShopController(ShopConnectionService connection, ShopOrderService orders) {
        this.connection = connection;
        this.orders = orders;
    }

    @PostMapping(path = "/api/integrations/shop/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ShopOrderResult> receive(@RequestHeader(name = "X-Shop-Key", required = false) String key,
            @Valid @RequestPart("order") ShopOrder order, MultipartHttpServletRequest request) {
        requireKey(key);
        Map<Integer, List<MultipartFile>> photos = new HashMap<>();
        request.getMultiFileMap().forEach((name, files) -> {
            Matcher m = ITEM_PART.matcher(name);
            if (m.matches()) {
                photos.put(Integer.parseInt(m.group(1)), files);
            }
        });
        ShopOrderResult result = orders.receive(order, photos);
        return ResponseEntity.status(result.outcome() == ShopDtos.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result);
    }

    /** For the shop's "Test the connection": 200 when the key is the current one, 401 otherwise. Changes nothing. */
    @GetMapping("/api/integrations/shop/ping")
    Map<String, Object> ping(@RequestHeader(name = "X-Shop-Key", required = false) String key) {
        requireKey(key);
        return Map.of("ok", true);
    }

    private void requireKey(String key) {
        if (!connection.accepts(key)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SHOP_KEY_INVALID",
                    "The shop key is missing or wrong. Make a new key in Shop connection and paste it into the shop.");
        }
    }

    // ---- Shop connection (admins) ----------------------------------------------------------------------

    @GetMapping("/api/shop-connection")
    @PreAuthorize("hasAuthority('SHOP_CONNECTION')")
    ConnectionView status() {
        var s = connection.status();
        return new ConnectionView(s.connected(), s.keyHint(), s.createdAt());
    }

    /** A new key, returned once; the old one stops working at once. */
    @Notifies(NotificationAction.SHOP_KEY_CHANGED)
    @PostMapping("/api/shop-connection/key")
    @PreAuthorize("hasAuthority('SHOP_CONNECTION')")
    NewKey newKey() {
        return new NewKey(connection.newKey(CurrentUser.id()));
    }

    @Notifies(NotificationAction.SHOP_DISCONNECTED)
    @DeleteMapping("/api/shop-connection/key")
    @PreAuthorize("hasAuthority('SHOP_CONNECTION')")
    ResponseEntity<Void> removeKey() {
        connection.removeKey();
        return ResponseEntity.noContent().build();
    }
}
