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
