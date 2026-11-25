package com.webshop.order.impl;

import com.webshop.common.Identifier;
import com.webshop.customer.api.Customer;
import com.webshop.order.api.Order;
import com.webshop.order.api.OrderService;
import com.webshop.order.spi.OrderRepository;
import com.webshop.payment.api.PaymentService;

import java.util.HashSet;
import java.util.Set;

public class OrderServiceImpl implements OrderService {

    private final OrderRepository repository;
    private final OrderProcessor processor;
    private final PaymentService payments;
    private final Set<Identifier> cancelled = new HashSet<>();
    private int nextId;

    public OrderServiceImpl(OrderRepository repository, PaymentService payments) {
        this.repository = repository;
        this.payments = payments;
        this.processor = new OrderProcessor(repository, payments);
    }

    @Override
    public Order create(Customer customer) {
        return new Order(new Identifier("O-" + nextId++), customer);
    }

    @Override
    public boolean place(Order order) {
        return processor.process(order);
    }

    @Override
    public void cancel(Identifier orderId) {
        Order order = repository.load(orderId);
        cancelled.add(order.id());
        payments.refund(order.id());
    }

    @Override
    public boolean isCancelled(Identifier orderId) {
        return cancelled.contains(orderId);
    }
}
