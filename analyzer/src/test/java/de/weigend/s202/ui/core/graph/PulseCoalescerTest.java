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

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-policy tests for {@link PulseCoalescer}: many markDirty calls in the
 * same pulse window must collapse into one flush; after the flush runs a new
 * markDirty starts a fresh window.
 */
class PulseCoalescerTest {

    /** Captures scheduled runnables; drain() simulates a pulse boundary. */
    private static final class FakeScheduler {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        java.util.function.Consumer<Runnable> consumer() {
            return queue::add;
        }

        int pending() {
            return queue.size();
        }

        /**
         * Run exactly the runnables already queued at entry. Runnables enqueued
         * during execution belong to the next pulse window and stay queued.
         */
        void drain() {
            int snapshot = queue.size();
            for (int i = 0; i < snapshot; i++) {
                queue.pollFirst().run();
            }
        }
    }

    @Test
    void firstMarkDirtySchedulesOneFlush() {
        FakeScheduler s = new FakeScheduler();
        AtomicInteger flushes = new AtomicInteger();
        PulseCoalescer c = new PulseCoalescer(s.consumer(), flushes::incrementAndGet);

        c.markDirty();

        assertEquals(1, s.pending(), "one scheduled runnable expected");
        assertEquals(0, flushes.get(), "flush must not have fired yet");
    }

    @Test
    void multipleMarkDirtyCallsCollapseIntoOneFlush() {
        FakeScheduler s = new FakeScheduler();
        AtomicInteger flushes = new AtomicInteger();
        PulseCoalescer c = new PulseCoalescer(s.consumer(), flushes::incrementAndGet);

        c.markDirty();
        c.markDirty();
        c.markDirty();
        c.markDirty();

        assertEquals(1, s.pending(), "only one scheduled runnable expected");
        s.drain();
        assertEquals(1, flushes.get(), "flush must have run exactly once");
    }

    @Test
    void afterFlushNextMarkDirtySchedulesAgain() {
        FakeScheduler s = new FakeScheduler();
        AtomicInteger flushes = new AtomicInteger();
        PulseCoalescer c = new PulseCoalescer(s.consumer(), flushes::incrementAndGet);

        c.markDirty();
        s.drain();
        c.markDirty();
        s.drain();

        assertEquals(2, flushes.get(), "each pulse window produces one flush");
    }

    @Test
    void markDirtyDuringFlushTriggersNextPulseFlush() {
        FakeScheduler s = new FakeScheduler();
        AtomicInteger flushes = new AtomicInteger();
        // Holder so the flush lambda can reference the coalescer
        PulseCoalescer[] holder = new PulseCoalescer[1];
        Runnable flush = () -> {
            flushes.incrementAndGet();
            if (flushes.get() == 1) {
                holder[0].markDirty();
            }
        };
        holder[0] = new PulseCoalescer(s.consumer(), flush);

        holder[0].markDirty();
        s.drain();

        assertEquals(1, flushes.get(), "first flush ran");
        assertEquals(1, s.pending(), "re-mark inside flush must have scheduled a follow-up");

        s.drain();
        assertEquals(2, flushes.get(), "follow-up flush ran in the next window");
    }
}
