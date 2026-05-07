package sccs.app;

import sccs.infra.MessageQueue;
import sccs.infra.PaymentRepository;

/**
 * Top-level entry point. Anchors PaymentRepository and MessageQueue
 * at the BOTTOM of their respective SCCs in the architecture-anchored strategy.
 */
public class AppMain {
    private final PaymentRepository repo;
    private final MessageQueue queue;

    public AppMain(PaymentRepository repo, MessageQueue queue) {
        this.repo = repo;
        this.queue = queue;
    }

    public void run() { repo.save("boot"); queue.enqueue("ready"); }
}
