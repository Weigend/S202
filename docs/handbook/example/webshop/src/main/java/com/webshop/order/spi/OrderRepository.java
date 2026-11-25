package com.webshop.order.spi;

import com.webshop.common.Identifier;
import com.webshop.order.api.Order;

/** Implemented by whoever stores the orders. */
public interface OrderRepository {

    void save(Order order);

    Order load(Identifier id);
}
