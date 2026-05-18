/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
