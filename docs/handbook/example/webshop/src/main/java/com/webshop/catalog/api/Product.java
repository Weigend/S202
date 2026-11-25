package com.webshop.catalog.api;

import com.webshop.common.Identifier;
import com.webshop.common.Money;

public record Product(Identifier id, String title, Money price) {
}
