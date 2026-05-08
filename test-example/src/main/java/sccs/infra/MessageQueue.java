package sccs.infra;

import sccs.api.NotificationController;
import sccs.core.Event;
import sccs.service.NotificationService;

/**
 * Infrastructure layer — correct bottom of the notification cycle.
 * AppMain depends on this, anchoring it LOW in the architecture-anchored strategy.
 *
 * WRONG back-edges: MessageQueue -> NotificationController / NotificationService / Event
 */
public class MessageQueue {
    private final NotificationController controller;
    private final NotificationService service;
    private final Event event;

    public MessageQueue(NotificationController controller, NotificationService service, Event event) {
        this.controller = controller;
        this.service = service;
        this.event = event;
    }

    public void enqueue(String message) { controller.notify("retry:" + message); }

    public void drain() { service.dispatch("drain"); event.payload("drain"); }
}
