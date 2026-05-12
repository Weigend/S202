package de.weigend.s202.ui;

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
final class PulseCoalescer {

    private final Consumer<Runnable> scheduler;
    private final Runnable flush;
    private boolean pending;

    PulseCoalescer(Consumer<Runnable> scheduler, Runnable flush) {
        this.scheduler = scheduler;
        this.flush = flush;
    }

    void markDirty() {
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
