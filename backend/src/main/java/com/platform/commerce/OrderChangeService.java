package com.platform.commerce;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A placed order changed in the chat, in ONE transaction: the old order gives its stock back, the new order takes what it needs, the old order is closed as replaced. Before this,
 * the new order was placed while the old one still held its pieces, so a change that kept an item whose last pieces the old order held was refused for «not enough stock»; and a
 * failure in between could leave both orders open. Now any failure undoes all of it and the old order stays exactly as it was.
 */
@Service
public class OrderChangeService {
    private final CheckoutService checkout;
    private final OrderService orders;

    public OrderChangeService(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @Transactional
    public Map<String, Object> placeReplacing(UUID tenantId, UUID userId, CheckoutService.CheckoutReq req, String key, long extraDiscountMinor, String source, UUID replaces) {
        if (key != null && !key.isBlank()) {   // the same confirmation again (a retry): that order was already placed and the old one closed
            Map<String, Object> prior = checkout.existingByKey(tenantId, key);
            if (prior != null) return prior;
        }
        String from = orders.releaseForReplacement(tenantId, replaces);
        Map<String, Object> res = checkout.placeOrder(tenantId, userId, req, key, extraDiscountMinor, source);
        orders.closeReplaced(tenantId, replaces, from, String.valueOf(res.get("orderNumber")));
        return res;
    }
}
