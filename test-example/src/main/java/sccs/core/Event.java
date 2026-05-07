package sccs.core;

import sccs.infra.MessageQueue;

/**
 * Correct dep: Event -> MessageQueue (core -> infra).
 * Same rank pattern as Order -> PaymentRepository.
 */
public class Event {
    private final MessageQueue queue;

    public Event(MessageQueue queue) { this.queue = queue; }

    public String payload(String type) {
        queue.enqueue("event:" + type);
        return type;
    }
}
