package sccs.api;

import sccs.platform.Infrastructure;
import sccs.service.PaymentService;

public class PaymentController {
    private final Infrastructure infra;
    private final PaymentService service;

    public PaymentController(Infrastructure infra, PaymentService service) {
        this.infra = infra;
        this.service = service;
    }

    public void handle(String request) {
        infra.config("payment");
        service.process(request);
    }
}
