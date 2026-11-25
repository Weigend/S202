package com.webshop.customer.api;

import com.webshop.common.Identifier;

import java.util.Optional;

public interface CustomerService {

    Optional<Customer> find(Identifier id);

    Customer register(String name);
}
