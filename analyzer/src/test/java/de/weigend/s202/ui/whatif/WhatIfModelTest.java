package de.weigend.s202.ui.whatif;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatIfModelTest {

    @Test
    void initialStateReflectsStaticEdges() {
        WhatIfModel model = new WhatIfModel(List.of(
                new ClassEdge("a.X", "b.Y", 1),
                new ClassEdge("b.Y", "c.Z", 1)));

        assertEquals(2, model.aggregator().size());
        assertEquals(2, model.graph().levelOf("a"));
        assertEquals(1, model.graph().levelOf("b"));
        assertEquals(0, model.graph().levelOf("c"));
    }

    @Test
    void applyMoveUpdatesGraphAndNotifiesListeners() {
        WhatIfModel model = new WhatIfModel(List.of(
                new ClassEdge("a.X", "b.Y", 1),
                new ClassEdge("b.Y", "c.Z", 1)));
        AtomicInteger notifications = new AtomicInteger();
        model.addChangeListener(notifications::incrementAndGet);

        // Move a.X virtually into c. As shown in VirtualPackageGraphTest,
        // this creates a b ↔ c cycle.
        model.applyMove("a.X", "c");

        assertEquals(1, notifications.get(), "exactly one change event per applyMove");
        assertTrue(model.graph().isInTangle("b"));
        assertTrue(model.graph().isInTangle("c"));
        assertEquals(model.graph().levelOf("b"), model.graph().levelOf("c"));
    }

    @Test
    void resetRevertsToStaticIdentityAndFiresOnce() {
        WhatIfModel model = new WhatIfModel(List.of(
                new ClassEdge("a.X", "b.Y", 1),
                new ClassEdge("b.Y", "c.Z", 1)));
        model.applyMove("a.X", "c");
        AtomicInteger notifications = new AtomicInteger();
        model.addChangeListener(notifications::incrementAndGet);

        model.reset();

        assertEquals(1, notifications.get());
        assertTrue(model.identity().isEmpty());
        // No more tangle, original levels restored.
        assertEquals(2, model.graph().levelOf("a"));
        assertEquals(1, model.graph().levelOf("b"));
        assertEquals(0, model.graph().levelOf("c"));
    }

    @Test
    void resetOnAlreadyCleanModelIsNoOpAndFiresNoEvent() {
        WhatIfModel model = new WhatIfModel(List.of(new ClassEdge("a.X", "b.Y", 1)));
        AtomicInteger notifications = new AtomicInteger();
        model.addChangeListener(notifications::incrementAndGet);

        model.reset();

        assertEquals(0, notifications.get());
    }

    @Test
    void removeMoveByPassingNullParent() {
        WhatIfModel model = new WhatIfModel(List.of(
                new ClassEdge("a.X", "b.Y", 1),
                new ClassEdge("b.Y", "c.Z", 1)));

        model.applyMove("a.X", "c");
        int sccCountAfterMove = model.graph().sccs().size();

        model.applyMove("a.X", null);
        int sccCountAfterRevert = model.graph().sccs().size();

        assertNotEquals(sccCountAfterMove, sccCountAfterRevert,
                "Reverting the override must visibly change the graph topology");
        assertTrue(model.identity().isEmpty());
    }
}
