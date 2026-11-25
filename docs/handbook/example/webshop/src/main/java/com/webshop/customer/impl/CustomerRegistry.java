package com.webshop.customer.impl;

import com.webshop.common.Identifier;
import com.webshop.customer.api.Customer;
import com.webshop.customer.api.CustomerService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CustomerRegistry implements CustomerService {

    private final Map<Identifier, Customer> customers = new HashMap<>();
    private final NamePolicy namePolicy = new NamePolicy();

    @Override
    public Optional<Customer> find(Identifier id) {
        return Optional.ofNullable(customers.get(id));
    }

    @Override
    public Customer register(String name) {
        namePolicy.check(name);
        Customer customer = new Customer(new Identifier("C-" + customers.size()), name);
        customers.put(customer.id(), customer);
        return customer;
    }
}
