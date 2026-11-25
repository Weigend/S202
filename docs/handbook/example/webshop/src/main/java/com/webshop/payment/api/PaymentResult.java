package com.webshop.payment.api;

import com.webshop.common.Identifier;

public record PaymentResult(Identifier orderId, boolean successful) {
}
