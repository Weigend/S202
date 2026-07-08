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
package de.weigend.s202.ui.core.graph;

import java.util.function.Consumer;

/**
 * Schedules at most one flush per pulse window: many {@link #markDirty()}
 * calls collapse into a single deferred {@code flush.run()}. While a flush
 * is already scheduled, further {@code markDirty()} calls are no-ops. After
 * the flush has fired the coalescer is ready for the next pulse window.
 * <p>
 * The scheduler is injected so the policy is testable without a JavaFX
 * runtime; in production the architecture view passes {@code Platform::runLater}.
 */
public final class PulseCoalescer {

    private final Consumer<Runnable> scheduler;
    private final Runnable flush;
    private boolean pending;

    public PulseCoalescer(Consumer<Runnable> scheduler, Runnable flush) {
        this.scheduler = scheduler;
        this.flush = flush;
    }

    public void markDirty() {
        if (pending) {
            return;
        }
        pending = true;
        scheduler.accept(() -> {
            pending = false;
            flush.run();
        });
    }
}
