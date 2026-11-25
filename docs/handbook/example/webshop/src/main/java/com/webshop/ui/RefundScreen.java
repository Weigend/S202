package com.webshop.ui;

import com.webshop.common.Identifier;
import com.webshop.order.api.OrderService;
import com.webshop.payment.impl.CardPayments;

/**
 * Cancels an order.
 *
 * <p>Deliberate defect: the screen talks to a class of the implementation of
 * payment instead of its API.
 */
public class RefundScreen {

    private final OrderService orders;
    private final CardPayments payments;

    public RefundScreen(OrderService orders, CardPayments payments) {
        this.orders = orders;
        this.payments = payments;
    }

    public void refund(Identifier orderId) {
        orders.cancel(orderId);
        payments.refund(orderId);
    }
}
