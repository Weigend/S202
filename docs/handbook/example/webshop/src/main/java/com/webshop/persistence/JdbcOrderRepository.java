package com.webshop.persistence;

import com.webshop.common.Identifier;
import com.webshop.order.api.Order;
import com.webshop.order.spi.OrderRepository;

import java.util.HashMap;
import java.util.Map;

public class JdbcOrderRepository implements OrderRepository {

    private final Map<Identifier, Order> table = new HashMap<>();
    private final ConnectionPool pool = new ConnectionPool();

    @Override
    public void save(Order order) {
        pool.acquire();
        table.put(order.id(), order);
    }

    @Override
    public Order load(Identifier id) {
        pool.acquire();
        return table.get(id);
    }
}
