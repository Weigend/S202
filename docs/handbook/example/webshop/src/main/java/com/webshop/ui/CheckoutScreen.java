package com.webshop.ui;

import com.webshop.catalog.api.CatalogService;
import com.webshop.catalog.api.Product;
import com.webshop.common.AuditLog;
import com.webshop.common.Identifier;
import com.webshop.customer.api.Customer;
import com.webshop.order.api.Order;
import com.webshop.order.api.OrderService;

public class CheckoutScreen {

    private final CatalogService catalog;
    private final OrderService orders;

    public CheckoutScreen(CatalogService catalog, OrderService orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    public void checkout(Customer customer, Identifier productId) {
        Product product = catalog.product(productId);
        Order order = orders.create(customer);
        order.add(product, 1);
        boolean placed = orders.place(order);
        AuditLog.record(customer, placed ? "ordered " + product.title() : "could not pay");
    }
}
