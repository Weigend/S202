package sccs.service;

import sccs.core.Order;
import sccs.infra.PaymentRepository;

public class PaymentService {
    private final Order order;
    private final PaymentRepository repo;

    public PaymentService(Order order, PaymentRepository repo) {
        this.order = order;
        this.repo = repo;
    }

    public void process(String request) { repo.save(order.id()); }
}
