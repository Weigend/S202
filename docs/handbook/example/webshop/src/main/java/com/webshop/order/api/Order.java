package com.webshop.order.api;

import com.webshop.catalog.api.Product;
import com.webshop.common.Identifier;
import com.webshop.common.Money;
import com.webshop.customer.api.Customer;

import java.util.ArrayList;
import java.util.List;

public class Order {

    private final Identifier id;
    private final Customer customer;
    private final List<OrderLine> lines = new ArrayList<>();

    public Order(Identifier id, Customer customer) {
        this.id = id;
        this.customer = customer;
    }

    public Identifier id() {
        return id;
    }

    public Customer customer() {
        return customer;
    }

    public void add(Product product, int quantity) {
        lines.add(new OrderLine(product, quantity));
    }

    public Money total() {
        Money total = Money.ZERO;
        for (OrderLine line : lines) {
            total = total.plus(line.price());
        }
        return total;
    }
}
