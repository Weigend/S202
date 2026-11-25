package com.webshop.catalog.impl;

import com.webshop.catalog.api.CatalogService;
import com.webshop.catalog.api.Product;
import com.webshop.common.Identifier;

import java.util.List;

public class CatalogServiceImpl implements CatalogService {

    private final ProductIndex index = new ProductIndex();
    private final ProductImporter importer = new ProductImporter(index);

    @Override
    public Product product(Identifier id) {
        importer.load();
        return index.get(id);
    }

    @Override
    public List<Product> search(String text) {
        importer.load();
        return index.matching(text);
    }

    @Override
    public ProductIndex index() {
        return index;
    }
}
