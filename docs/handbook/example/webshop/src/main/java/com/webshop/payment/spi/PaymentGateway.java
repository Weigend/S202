package com.webshop.payment.spi;

import com.webshop.common.Identifier;
import com.webshop.common.Money;

/** Implemented by whoever moves the money. */
public interface PaymentGateway {

    boolean transfer(Identifier reference, Money amount);
}
