package sccs.api;

import sccs.platform.Infrastructure;
import sccs.service.NotificationService;

public class NotificationController {
    private final Infrastructure infra;
    private final NotificationService service;

    public NotificationController(Infrastructure infra, NotificationService service) {
        this.infra = infra;
        this.service = service;
    }

    public void notify(String event) {
        infra.config("notification");
        service.dispatch(event);
    }
}
