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
