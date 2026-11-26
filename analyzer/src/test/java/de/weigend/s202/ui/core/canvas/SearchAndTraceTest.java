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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding an element opens the packages around it and selects it; marking the
 * users or dependencies of an element marks the boxes it reaches, also those
 * a closed package builds only later, and the strip below the picture comes
 * and goes with the marks.
 */
class SearchAndTraceTest {

    private static final Duration FX_TIMEOUT = Duration.ofSeconds(20);
    private static final PseudoClass TRACED = PseudoClass.getPseudoClass("traced");
    private static boolean javaFxStarted;

    private ArchitectureCanvas canvas;

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        Lookup.init();
        CountDownLatch ready = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    ready.countDown();
                });
            } catch (IllegalStateException alreadyStarted) {
                Platform.runLater(ready::countDown);
            } catch (Throwable unavailable) {
                // the latch times out
            }
        }, "javafx-test-starter");
        starter.setDaemon(true);
        starter.start();
        javaFxStarted = ready.await(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @BeforeEach
    void openTheShop() {
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");
        onFx(() -> {
            DependencyModel raw = shop();
            DomainModel model = new LevelCalculator().calculate(raw);
            canvas = new ArchitectureCanvas();
            canvas.setRawDependencyModel(raw);
            canvas.setDomainModel(model);
            canvas.setArchitectureRoot(new ArchitectureNodeBuilder().build(model));
            new Scene(canvas, 900, 700);
            canvas.applyCss();
            canvas.layout();
        });
        await(canvas::hasRoot);
    }

    @Test
    void findingAClassOpensItsPackageAndSelectsIt() {
        onFx(() -> pkg("com.shop.order").setExpanded(false));

        onFx(() -> assertEquals("com.shop.order.Line", canvas.reveal("com.shop.order.Line")));

        onFx(() -> {
            assertTrue(pkg("com.shop.order").isExpanded());
            assertEquals("com.shop.order.Line", canvas.getSelectedFullName());
            assertTrue(find(canvas, LevelClassBox.class, "com.shop.order.Line").isVisible());
        });
    }

    @Test
    void theSearchFindsByInitialsAndRevealsTheBestHit() {
        onFx(() -> pkg("com.shop.base").setExpanded(false));

        onFx(() -> assertEquals("com.shop.base.Money", canvas.find("Mon")));

        onFx(() -> {
            assertTrue(pkg("com.shop.base").isExpanded());
            assertEquals("com.shop.base.Money", canvas.getSelectedFullName());
        });
    }

    @Test
    void whatTheViewDoesNotShowIsNotFound() {
        onFx(() -> assertNull(canvas.reveal("com.other.Nothing")));
    }

    @Test
    void theUsersOfAClassAreMarkedThroughOthers() {
        onFx(() -> assertTrue(canvas.markTransitive("com.shop.base.Money", true)));
        onFx(() -> assertEquals("com.shop.base.Money", canvas.getSelectedFullName(), "the start is selected"));

        onFx(() -> assertEquals(Set.of("com.shop.order", "com.shop.order.Order", "com.shop.order.Line",
                "com.shop.ui", "com.shop.ui.Screen", "com.shop.base", "com.shop.base.Audit"),
                withoutTop(canvas.getMarked()), "Audit uses Order, which uses Money"));
        await(() -> traced(find(canvas, LevelClassBox.class, "com.shop.ui.Screen")));
        onFx(() -> {
            assertFalse(traced(find(canvas, LevelClassBox.class, "com.shop.base.Money")), "the start is not marked");
            assertNotNull(canvas.getBottom(), "the strip below the picture");
        });

        onFx(canvas::clearMarks);

        await(() -> !traced(find(canvas, LevelClassBox.class, "com.shop.ui.Screen")));
        onFx(() -> assertNull(canvas.getBottom()));
    }

    @Test
    void boxesBuiltLaterAreMarkedToo() {
        onFx(() -> {
            pkg("com.shop.order").setExpanded(false);
            canvas.markTransitive("com.shop.ui.Screen", false);
        });
        await(() -> pkg("com.shop.order").getStyle().contains("#00838f"));

        onFx(() -> pkg("com.shop.order").setExpanded(true));

        await(() -> traced(find(canvas, LevelClassBox.class, "com.shop.order.Line")));
    }

    @Test
    void anotherPlanAsksAgain() {
        onFx(() -> canvas.markTransitive("com.shop.base.Money", true));

        onFx(() -> {
            canvas.applyEdgeCut("com.shop.order.Order", "com.shop.base.Money");
            canvas.applyEdgeCut("com.shop.order.Line", "com.shop.base.Money");
        });

        onFx(() -> {
            assertEquals(2, canvas.getPlan().cuts().size(), "both cuts are planned and shown");
            assertEquals(Set.of(), canvas.getMarked(), "with both cut, nothing uses Money");
            assertNotNull(canvas.getBottom(), "the trace stays and says so");
        });
    }

    private static Set<String> withoutTop(Set<String> marked) {
        return Set.copyOf(marked.stream().filter(name -> name.startsWith("com.shop.")).toList());
    }

    private static boolean traced(Node box) {
        return box != null && box.getPseudoClassStates().contains(TRACED);
    }

    private LevelPackageBox pkg(String name) {
        LevelPackageBox box = find(canvas, LevelPackageBox.class, name);
        assertNotNull(box, name);
        return box;
    }

    private static <T extends Node> T find(Node node, Class<T> type, String fullName) {
        if (type.isInstance(node) && node instanceof GraphSelection.Selectable selectable
                && fullName.equals(selectable.getFullName())) {
            return type.cast(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = find(child, type, fullName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** ui uses order, order uses base, and base uses order back. */
    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.ui.Screen", "com.shop.order.Order");
        add(raw, "com.shop.order.Order", "com.shop.order.Line", "com.shop.base.Money");
        add(raw, "com.shop.order.Line", "com.shop.base.Money");
        add(raw, "com.shop.base.Money");
        add(raw, "com.shop.base.Audit", "com.shop.order.Order");
        PackageHierarchyBuilder.buildPackageHierarchy(raw);
        return raw;
    }

    private static void add(DependencyModel raw, String name, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        for (String target : targets) {
            info.addDependency(target, EdgeKind.CALLS);
        }
        raw.addClass(name, info);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + FX_TIMEOUT.toNanos();
        boolean[] met = new boolean[1];
        while (!met[0] && System.nanoTime() < deadline) {
            onFx(() -> met[0] = condition.getAsBoolean());
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(met[0], "Condition was not met within " + FX_TIMEOUT);
    }

    private static void onFx(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError error) {
                throw error;
            }
            throw new AssertionError(e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
