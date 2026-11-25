package com.webshop.order.impl;

import com.webshop.order.api.Order;

class OrderValidator {

    private final OrderProcessor processor;

    OrderValidator(OrderProcessor processor) {
        this.processor = processor;
    }

    void checkCustomer(Order order) {
        if (order.customer() == null) {
            throw new IllegalArgumentException("an order needs a customer");
        }
    }

    void checkTotal(Order order) {
        if (!order.total().isPositive() || order.total().cents() > processor.limit()) {
            throw new IllegalArgumentException("the total is not valid");
        }
    }
}
