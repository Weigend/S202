package com.webshop.app;

import com.webshop.catalog.api.CatalogService;
import com.webshop.common.Identifier;
import com.webshop.customer.api.Customer;
import com.webshop.customer.api.CustomerService;
import com.webshop.order.api.OrderService;
import com.webshop.ui.CheckoutScreen;

import java.util.ServiceLoader;

/** Starts the shop. The services are found, not created, so nothing here knows an implementation. */
public final class ShopApplication {

    private ShopApplication() {
    }

    public static void main(String[] args) {
        CustomerService customers = service(CustomerService.class);
        CheckoutScreen checkout =
                new CheckoutScreen(service(CatalogService.class), service(OrderService.class));
        Customer customer = customers.register("Ada");
        checkout.checkout(customer, new Identifier("P-1"));
    }

    private static <T> T service(Class<T> type) {
        return ServiceLoader.load(type).findFirst().orElseThrow();
    }
}
