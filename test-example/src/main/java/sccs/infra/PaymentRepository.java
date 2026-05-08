package sccs.infra;

import sccs.api.PaymentController;
import sccs.core.Order;
import sccs.service.PaymentService;

/**
 * Infrastructure layer — correct bottom of the payment cycle.
 * AppMain depends on this, anchoring it LOW in the architecture-anchored strategy.
 *
 * WRONG back-edges: PaymentRepository -> PaymentController / PaymentService / Order
 * These give PaymentRepository rank = +0.20 internally, fooling the heuristic
 * into treating it as a high-level class.
 */
public class PaymentRepository {
    private final PaymentController controller;
    private final PaymentService service;
    private final Order order;

    public PaymentRepository(PaymentController controller, PaymentService service, Order order) {
        this.controller = controller;
        this.service = service;
        this.order = order;
    }

    public void save(String data) { controller.handle("retry:" + data); }

    public String nextId() { return "id-1"; }

    public void flush() { service.process("flush"); order.id(); }
}
