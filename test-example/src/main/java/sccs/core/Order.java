package sccs.core;

import sccs.infra.PaymentRepository;

/**
 * Correct dep: Order -> PaymentRepository (core -> infra).
 * The heuristic wrongly cuts this edge (rank mismatch due to violation edges on PaymentRepository).
 */
public class Order {
    private final PaymentRepository repo;

    public Order(PaymentRepository repo) { this.repo = repo; }

    public String id() { return repo.nextId(); }
}
