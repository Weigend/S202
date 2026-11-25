package com.webshop.catalog.impl;

import com.webshop.catalog.api.Product;
import com.webshop.common.Identifier;
import com.webshop.common.Money;

class ProductImporter {

    private final ProductIndex index;

    ProductImporter(ProductIndex index) {
        this.index = index;
    }

    void load() {
        index.add(new Product(new Identifier("P-1"), "Teapot", new Money(2490)));
        index.add(new Product(new Identifier("P-2"), "Cup", new Money(890)));
    }
}
