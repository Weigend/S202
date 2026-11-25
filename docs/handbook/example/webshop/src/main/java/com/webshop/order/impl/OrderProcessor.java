package com.webshop.order.impl;

import com.webshop.order.api.Order;
import com.webshop.order.spi.OrderRepository;
import com.webshop.payment.api.PaymentResult;
import com.webshop.payment.api.PaymentService;

/**
 * Takes an order through its steps.
 *
 * <p>Deliberate defect: {@link OrderValidator} calls back to ask for the limit,
 * so the two classes form a class cycle inside their package.
 */
class OrderProcessor {

    private static final int MAX_CENTS = 1_000_000;

    private final OrderRepository repository;
    private final PaymentService payments;
    private final OrderValidator validator = new OrderValidator(this);

    OrderProcessor(OrderRepository repository, PaymentService payments) {
        this.repository = repository;
        this.payments = payments;
    }

    boolean process(Order order) {
        validator.checkCustomer(order);
        validator.checkTotal(order);
        PaymentResult result = payments.authorize(order.id(), order.total());
        if (result.successful()) {
            payments.capture(order.id());
            repository.save(order);
        }
        return result.successful();
    }

    int limit() {
        return MAX_CENTS;
    }
}
