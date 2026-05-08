package sccs.service;

import sccs.core.Event;
import sccs.infra.MessageQueue;

public class NotificationService {
    private final Event event;
    private final MessageQueue queue;

    public NotificationService(Event event, MessageQueue queue) {
        this.event = event;
        this.queue = queue;
    }

    public void dispatch(String type) { queue.enqueue(event.payload(type)); }
}
