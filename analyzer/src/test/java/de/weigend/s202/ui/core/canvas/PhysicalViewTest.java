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
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.PhysicalHierarchy;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.layout.horizontal.HorizontalRowLayoutOptimizer;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Physical View of a shop in three files: the files are the outermost
 * boxes, the packages that only pass through below them have none, a package
 * with classes in two files says so, and the cuts of the document reach the
 * view under its names.
 */
class PhysicalViewTest {

    private static final Duration FX_TIMEOUT = Duration.ofSeconds(20);
    private static final String ORDER = file("order.jar");
    private static final String BASE = file("base.jar");
    private static boolean javaFxStarted;

    private ArchitectureCanvas canvas;
    private PhysicalHierarchy hierarchy;

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
    void openThePhysicalView() {
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");
        hierarchy = PhysicalHierarchy.of(shop());
        DomainModel model = new LevelCalculator().calculate(hierarchy.model());
        ArchitectureNode root = new ArchitectureNodeBuilder().build(model);
        new HorizontalRowLayoutOptimizer().assignHorizontalLayoutOrders(root);
        onFx(() -> {
            canvas = new ArchitectureCanvas();
            canvas.setOwnName(hierarchy::toPhysical);
            canvas.setRawDependencyModel(hierarchy.model());
            canvas.setDomainModel(model);
            canvas.setArchitectureRoot(root);
            new Scene(canvas, 900, 700);
            canvas.applyCss();
            canvas.layout();
        });
        await(canvas::hasRoot);
    }

    @Test
    void theFilesAreTheOutermostBoxes() {
        onFx(() -> {
            assertNotNull(find(canvas, LevelPackageBox.class, ORDER));
            assertNotNull(find(canvas, LevelPackageBox.class, BASE));
        });
    }

    @Test
    void packagesThatOnlyPassThroughHaveNoBox() {
        onFx(() -> {
            assertEquals(null, find(canvas, LevelPackageBox.class, ORDER + ".com"), "com holds only shop");
            assertNotNull(find(canvas, LevelPackageBox.class, ORDER + ".com.shop.order"));
            LevelPackageBox shop = find(canvas, LevelPackageBox.class, ORDER + ".com.shop");
            assertSame(shop, packageAround(find(canvas, LevelPackageBox.class, ORDER + ".com.shop.order")));
            assertSame(find(canvas, LevelPackageBox.class, ORDER), packageAround(shop),
                    "shop, which holds two packages, lies right in the box of the file");
        });
    }

    @Test
    void aSplitPackageSaysSo() {
        onFx(() -> {
            assertTrue(label(find(canvas, LevelPackageBox.class, ORDER + ".com.shop.base")).contains("split"));
            assertTrue(label(find(canvas, LevelPackageBox.class, BASE + ".com.shop.base")).contains("split"));
            assertFalse(label(find(canvas, LevelPackageBox.class, ORDER + ".com.shop.order")).contains("split"));
        });
    }

    @Test
    void theCutsOfTheDocumentArriveUnderTheNamesOfTheView() {
        onFx(() -> canvas.applyEdgeCut("com.shop.base.Audit", "com.shop.order.Order"));

        onFx(() -> assertEquals(List.of(new DependencyEdge(BASE + ".com.shop.base.Audit", ORDER + ".com.shop.order.Order")),
                List.copyOf(canvas.getPlan().cuts())));
    }

    private static String file(String name) {
        return name.replace('.', PhysicalHierarchy.DOT);
    }

    /** The texts of the header: toggle, name and level. */
    private static String label(LevelPackageBox box) {
        StringBuilder text = new StringBuilder();
        for (Label label : all(box.getChildren().get(0), Label.class, new ArrayList<>())) {
            text.append(label.getText()).append(' ');
        }
        return text.toString();
    }

    private static LevelPackageBox packageAround(Node node) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof LevelPackageBox box) {
                return box;
            }
        }
        return null;
    }

    private static <T> List<T> all(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> all(child, type, found));
        }
        return found;
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

    /** order.jar: com.shop.order and half of com.shop.base; base.jar: the other half, using order back. */
    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.order.Order", "order.jar", "com.shop.base.Money");
        add(raw, "com.shop.base.Money", "order.jar");
        add(raw, "com.shop.base.Audit", "base.jar", "com.shop.order.Order");
        return raw;
    }

    private static void add(DependencyModel raw, String name, String origin, String... targets) {
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                name, name.substring(name.lastIndexOf('.') + 1), name.substring(0, name.lastIndexOf('.')));
        info.setOrigin(origin);
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
