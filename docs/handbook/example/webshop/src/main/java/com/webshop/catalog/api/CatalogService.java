package com.webshop.catalog.api;

import com.webshop.catalog.impl.ProductIndex;
import com.webshop.common.Identifier;

import java.util.List;

/**
 * The products of the shop.
 *
 * <p>Deliberate defect: {@link #index()} hands out a class of the
 * implementation, so whoever uses the API depends on it as well.
 */
public interface CatalogService {

    Product product(Identifier id);

    List<Product> search(String text);

    ProductIndex index();
}
