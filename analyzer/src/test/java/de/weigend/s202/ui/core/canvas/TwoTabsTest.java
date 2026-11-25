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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import de.weigend.s202.ui.core.graph.ArchitectureViewSettings;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.views.component.ComponentBox;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.shape.Shape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two tabs in one pane. The one behind keeps its arrows: JavaFX makes the
 * content of a tab that is not selected invisible, but a setting or a cut that
 * concerns all tabs redraws the arrows there as well. What happens to a box
 * concerns its own tab only, whichever was built last. And a closed tab is
 * gone.
 */
class TwoTabsTest {

    private static final Duration FX_TIMEOUT = Duration.ofSeconds(20);

    /** The three dependencies of {@link #root()}. */
    private static final int ARROWS = 3;

    private static boolean javaFxStarted;

    private ArchitectureCanvas front;
    private ArchitectureCanvas behind;
    private TabPane tabs;

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        Lookup.init();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    ready.countDown();
                });
            } catch (IllegalStateException alreadyStarted) {
                Platform.runLater(ready::countDown);
            } catch (Throwable t) {
                failure.set(t);
                ready.countDown();
            }
        }, "javafx-test-starter");
        starter.setDaemon(true);
        starter.start();
        javaFxStarted = ready.await(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) && failure.get() == null;
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @BeforeEach
    void openTwoTabs() {
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");
        onFx(() -> {
            ArchitectureViewSettings.setShowArchitectureLevel(false);
            behind = new ArchitectureCanvas();
            front = new ArchitectureCanvas();
            tabs = new TabPane(new Tab("behind", behind), new Tab("front", front));
            new Scene(tabs, 800, 600);
            behind.setArchitectureRoot(root());
            front.setArchitectureRoot(root());
        });
        await(() -> behind.hasRoot() && front.hasRoot());
        showWithArrows(behind);
        showWithArrows(front);
        assertFalse(behind.getParent().isVisible(), "the pane hides the tab behind");
    }

    @AfterEach
    void restoreSettings() {
        if (javaFxStarted) {
            onFx(() -> ArchitectureViewSettings.setShowArchitectureLevel(false));
        }
    }

    @Test
    void theArchitectureLevelsLeaveTheArrowsOfTheOtherTab() {
        long before = redraws(behind);
        onFx(() -> {
            ArchitectureViewSettings.setShowArchitectureLevel(true);
            layout();
        });

        await(() -> redraws(behind) > before);

        assertArrowsKept();
    }

    @Test
    void aCutLeavesTheArrowsOfTheOtherTab() {
        long before = redraws(behind);
        onFx(() -> {
            front.applyEdgeCuts(Set.of(new DependencyEdge("pkg.A", "pkg.B")));
            behind.applyEdgeCuts(Set.of(new DependencyEdge("pkg.A", "pkg.B")));
        });

        await(() -> redraws(behind) > before);

        assertArrowsKept();
    }

    @Test
    void theCyclesOfTheOtherTabStay() {
        select(behind);
        onFx(() -> {
            behind.setShowDependencies(false);
            behind.setShowScc(true);
        });
        await(() -> arrows(behind) > ARROWS);
        long withCycles = arrows(behind);
        select(front);
        long before = redraws(behind);
        onFx(behind::redrawDependencyArrows);

        await(() -> redraws(behind) > before);

        onFx(() -> assertEquals(withCycles, arrows(behind), "the tab behind lost its cycles"));
    }

    /**
     * The other package is higher, so the picture keeps its size and nothing
     * but the box itself tells the view that the arrow has a new end.
     */
    @Test
    void closingAPackageRedrawsTheArrowsOfItsOwnTab() {
        for (ArchitectureCanvas canvas : List.of(behind, front)) {
            onFx(() -> canvas.setArchitectureRoot(highAndLowPackage()));
            showWithArrows(canvas);
        }
        select(behind);
        long before = redraws(behind);
        long beforeInFront = redraws(front);
        AtomicReference<List<String>> open = new AtomicReference<>();
        onFx(() -> {
            open.set(lines(behind));
            box(behind, LevelPackageBox.class, "low").setExpanded(false);
        });

        await(() -> redraws(behind) > before);

        onFx(() -> {
            assertNotEquals(open.get(), lines(behind), "the arrow ends at the closed package now");
            assertEquals(beforeInFront, redraws(front), "the other tab has nothing to draw");
        });
    }

    @Test
    void closingAComponentConcernsItsOwnTabOnly() throws Exception {
        onFx(() -> {
            behind.setViewStyle(ArchitectureKind.COMPONENT);
            front.setViewStyle(ArchitectureKind.COMPONENT);
        });
        showAnalysis(behind);
        showAnalysis(front);
        select(behind);
        // Until everything the setup asked for is drawn.
        onFx(() -> { });
        onFx(() -> { });
        long before = redraws(behind);
        long beforeInFront = redraws(front);
        onFx(() -> box(behind, ComponentBox.class, "sccs").setExpanded(false));

        await(() -> redraws(behind) > before);

        onFx(() -> assertEquals(beforeInFront, redraws(front), "the other tab has nothing to draw"));
    }

    @Test
    void aDroppedBoxIsAMoveInItsOwnTabOnly() throws Exception {
        showAnalysis(behind);
        showAnalysis(front);
        select(behind);
        onFx(() -> {
            LevelClassBox dragged = box(behind, LevelClassBox.class, "com.example.C");
            Node destinationRow = box(behind, LevelClassBox.class, "com.example1.X").getParent();

            drag(dragged, destinationRow);

            assertSame(destinationRow, dragged.getParent());
            assertTrue(behind.canUndoWhatIfProperty().get());
            assertFalse(front.canUndoWhatIfProperty().get());
        });
    }

    /** Both at once: how long the pane holds on to a removed tab is not the question. */
    @Test
    void closedTabsAreNotKeptInMemory() throws Exception {
        showAnalysis(behind);
        showAnalysis(front);
        List<WeakReference<ArchitectureCanvas>> closed =
                List.of(new WeakReference<>(behind), new WeakReference<>(front));
        onFx(() -> {
            behind = null;
            front = null;
            tabs = null;
        });

        await(() -> {
            System.gc();
            return closed.stream().allMatch(canvas -> canvas.get() == null);
        });
    }

    private void showAnalysis(ArchitectureCanvas canvas) throws Exception {
        DependencyModel raw = new InputAnalyzer().analyze("../test-example/target/test-example-1.6.0.jar");
        DomainModel domain = Lookup.lookup(DomainComputer.class).compute(raw);
        ArchitectureNode root = new ArchitectureNodeBuilder().build(domain);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        onFx(() -> {
            canvas.setRawDependencyModel(raw);
            canvas.setDomainModel(domain);
            canvas.setArchitectureRoot(root);
        });
        select(canvas);
    }

    /** What the mouse does: press on the box, drag it over the row, release it there. */
    private static void drag(Node box, Node row) {
        Event.fireEvent(box, mouse(MouseEvent.MOUSE_PRESSED, 0, new PickResult(box, 0, 0)));
        Event.fireEvent(box, mouse(MouseEvent.MOUSE_DRAGGED, 50, new PickResult(row, 50, 50)));
        Event.fireEvent(box, mouse(MouseEvent.MOUSE_RELEASED, 50, new PickResult(row, 50, 50)));
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, double position, PickResult pick) {
        return new MouseEvent(type, position, position, position, position, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, pick);
    }

    private static <T extends Node> T box(Node node, Class<T> type, String fullName) {
        T found = findBox(node, type, fullName);
        assertNotNull(found, fullName);
        return found;
    }

    private static <T extends Node> T findBox(Node node, Class<T> type, String fullName) {
        if (type.isInstance(node) && node instanceof GraphSelection.Selectable selectable
                && fullName.equals(selectable.getFullName())) {
            return type.cast(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = findBox(child, type, fullName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void assertArrowsKept() {
        AtomicReference<List<String>> drawnBehind = new AtomicReference<>();
        onFx(() -> {
            assertEquals(ARROWS, arrows(front));
            assertEquals(ARROWS, arrows(behind), "the tab behind lost its arrows");
        });
        select(behind);
        onFx(() -> drawnBehind.set(lines(behind)));
        long before = redraws(behind);
        onFx(behind::redrawDependencyArrows);
        await(() -> redraws(behind) > before);
        onFx(() -> assertEquals(lines(behind), drawnBehind.get(), "drawn behind, the arrows have to fit the boxes too"));
    }

    private void showWithArrows(ArchitectureCanvas canvas) {
        select(canvas);
        onFx(() -> canvas.setShowDependencies(true));
        await(() -> arrows(canvas) == ARROWS);
    }

    private void select(ArchitectureCanvas canvas) {
        onFx(() -> select(canvas == behind ? 0 : 1));
    }

    private void select(int tab) {
        tabs.getSelectionModel().select(tab);
        layout();
    }

    private void layout() {
        tabs.applyCss();
        tabs.layout();
    }

    private static ArchitectureNode root() {
        ArchitectureNode root = new ArchitectureNode("root", "root", ArchitectureNode.NodeType.PACKAGE, true, 0);
        ArchitectureNode pkg = new ArchitectureNode("pkg", "pkg", ArchitectureNode.NodeType.PACKAGE, true, 0);
        pkg.addChild(cls("pkg", "A", 2, Set.of("pkg.B")));
        pkg.addChild(cls("pkg", "B", 1, Set.of("pkg.C")));
        pkg.addChild(cls("pkg", "C", 0, Set.of("pkg.B")));
        root.addChild(pkg);
        return root;
    }

    /** Two packages with three dependencies; the second package is lower than the first. */
    private static ArchitectureNode highAndLowPackage() {
        ArchitectureNode root = new ArchitectureNode("root", "root", ArchitectureNode.NodeType.PACKAGE, true, 0);
        ArchitectureNode high = new ArchitectureNode("high", "high", ArchitectureNode.NodeType.PACKAGE, true, 0);
        high.addChild(cls("high", "A", 2, Set.of("high.B")));
        high.addChild(cls("high", "B", 1, Set.of("high.C")));
        high.addChild(cls("high", "C", 0, Set.of("low.D")));
        ArchitectureNode low = new ArchitectureNode("low", "low", ArchitectureNode.NodeType.PACKAGE, true, 0);
        low.addChild(cls("low", "D", 0, Set.of()));
        root.addChild(high);
        root.addChild(low);
        return root;
    }

    private static ArchitectureNode cls(String packageName, String name, int level, Set<String> dependencies) {
        ArchitectureNode node = new ArchitectureNode(
                packageName + "." + name, name, ArchitectureNode.NodeType.CLASS, false, level);
        node.setArchitectureLevel(level);
        node.setDependencies(dependencies);
        return node;
    }

    private static long arrows(ArchitectureCanvas canvas) {
        return lines(canvas).size();
    }

    /** The lines that can be clicked, one per arrow, with where they run. */
    private static List<String> lines(Node node) {
        if (node instanceof Shape) {
            return node.getOnMouseClicked() != null ? List.of(node.getBoundsInLocal().toString()) : List.of();
        }
        return node instanceof Parent parent
                ? parent.getChildrenUnmodifiable().stream().flatMap(child -> lines(child).stream()).toList()
                : List.of();
    }

    private static long redraws(ArchitectureCanvas canvas) {
        return canvas.redrawTickProperty().getValue().longValue();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + FX_TIMEOUT.toNanos();
        AtomicReference<Boolean> met = new AtomicReference<>(false);
        while (System.nanoTime() < deadline) {
            onFx(() -> met.set(condition.getAsBoolean()));
            if (met.get()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting", e);
            }
        }
        fail("Condition was not met within " + FX_TIMEOUT);
    }

    private static void onFx(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for JavaFX", e);
        } catch (TimeoutException e) {
            fail("Timed out while waiting for JavaFX", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw new AssertionError(e.getCause());
        }
    }
}
