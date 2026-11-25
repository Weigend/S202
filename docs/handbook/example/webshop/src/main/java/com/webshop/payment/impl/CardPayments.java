package com.webshop.payment.impl;

import com.webshop.common.Identifier;
import com.webshop.common.Money;
import com.webshop.payment.api.PaymentResult;
import com.webshop.payment.api.PaymentService;
import com.webshop.payment.spi.PaymentGateway;

public class CardPayments implements PaymentService {

    private final PaymentGateway gateway;
    private final RefundPolicy refundPolicy;

    public CardPayments(PaymentGateway gateway, RefundPolicy refundPolicy) {
        this.gateway = gateway;
        this.refundPolicy = refundPolicy;
    }

    @Override
    public PaymentResult authorize(Identifier orderId, Money amount) {
        return new PaymentResult(orderId, gateway.transfer(orderId, amount));
    }

    @Override
    public PaymentResult capture(Identifier orderId) {
        return new PaymentResult(orderId, true);
    }

    @Override
    public PaymentResult refund(Identifier orderId) {
        return new PaymentResult(orderId, refundPolicy.allows(orderId));
    }
}
