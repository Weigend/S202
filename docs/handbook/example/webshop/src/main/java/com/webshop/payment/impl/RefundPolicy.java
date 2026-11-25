package com.webshop.payment.impl;

import com.webshop.common.Identifier;
import com.webshop.order.api.OrderService;

/**
 * Decides whether an order may be refunded.
 *
 * <p>Deliberate defect: payment asks the order component back, which uses
 * payment itself. The two components form a package cycle.
 */
public class RefundPolicy {

    private final OrderService orders;

    public RefundPolicy(OrderService orders) {
        this.orders = orders;
    }

    boolean allows(Identifier orderId) {
        return orders.isCancelled(orderId);
    }
}
