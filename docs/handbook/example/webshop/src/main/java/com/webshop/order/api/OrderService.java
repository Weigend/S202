package com.webshop.order.api;

import com.webshop.common.Identifier;
import com.webshop.customer.api.Customer;

public interface OrderService {

    Order create(Customer customer);

    boolean place(Order order);

    void cancel(Identifier orderId);

    boolean isCancelled(Identifier orderId);
}
