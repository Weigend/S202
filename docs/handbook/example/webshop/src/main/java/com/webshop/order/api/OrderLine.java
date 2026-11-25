package com.webshop.order.api;

import com.webshop.catalog.api.Product;
import com.webshop.common.Money;

public record OrderLine(Product product, int quantity) {

    public Money price() {
        return product.price().times(quantity);
    }
}
