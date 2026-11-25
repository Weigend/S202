package com.webshop.catalog.impl;

import com.webshop.catalog.api.Product;
import com.webshop.common.Identifier;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ProductIndex {

    private final Map<String, Product> byKey = new TreeMap<>();

    void add(Product product) {
        byKey.put(product.id().value(), product);
    }

    Product get(Identifier id) {
        return byKey.get(id.value());
    }

    List<Product> matching(String text) {
        return byKey.values().stream().filter(p -> p.title().contains(text)).toList();
    }
}
