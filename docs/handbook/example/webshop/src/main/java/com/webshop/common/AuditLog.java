package com.webshop.common;

import com.webshop.customer.api.Customer;

/**
 * Writes down who did what.
 *
 * <p>Deliberate defect: the foundation knows a business class. {@code Customer}
 * calls this class in turn, so the two form a class cycle across packages.
 */
public final class AuditLog {

    private AuditLog() {
    }

    public static void record(Customer who, String what) {
        System.out.println(who.name() + ": " + what);
    }

    public static void record(String what) {
        System.out.println(what);
    }
}
