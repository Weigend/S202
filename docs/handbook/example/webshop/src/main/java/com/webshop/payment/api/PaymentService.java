package com.webshop.payment.api;

import com.webshop.common.Identifier;
import com.webshop.common.Money;

public interface PaymentService {

    PaymentResult authorize(Identifier orderId, Money amount);

    PaymentResult capture(Identifier orderId);

    PaymentResult refund(Identifier orderId);
}
