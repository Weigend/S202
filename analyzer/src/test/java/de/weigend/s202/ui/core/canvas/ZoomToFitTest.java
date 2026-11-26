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

import de.weigend.s202.ui.core.model.ArchitectureNode;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoomToFitTest {

    private static final double WIDTH = 800;
    private static final double HEIGHT = 600;

    private static boolean javaFxStarted;

    private ArchitectureCanvas canvas;

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
        javaFxStarted = ready.await(20, TimeUnit.SECONDS) && failure.get() == null;
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void aPictureLargerThanTheWindowShrinksUntilAllOfItIsVisible() throws Exception {
        show(60);
        onFx(() -> assertTrue(visibleScrollBars() > 0, "at 100 % the picture does not fit"));

        fit();

        onFx(() -> {
            assertTrue(canvas.zoomFactorProperty().get() < 1.0);
            assertFills();
        });
    }

    @Test
    void aSmallPictureGrowsUntilItFillsTheWindow() throws Exception {
        show(2);

        fit();

        onFx(() -> {
            assertTrue(canvas.zoomFactorProperty().get() > 1.0);
            assertFills();
        });
    }

    @Test
    void fittingTwiceChangesNothing() throws Exception {
        show(60);
        fit();
        double first = canvas.zoomFactorProperty().get();

        fit();

        assertEquals(first, canvas.zoomFactorProperty().get(), 0.001);
    }

    private void assertFills() {
        Bounds picture = canvas.snapshotContentNode().getBoundsInParent();
        assertEquals(0, visibleScrollBars(), "all of it is visible");
        assertTrue(picture.getWidth() <= WIDTH && picture.getHeight() <= HEIGHT);
        assertTrue(picture.getWidth() > WIDTH - 10 || picture.getHeight() > HEIGHT - 10,
                () -> "as large as possible, but is " + picture);
    }

    private long visibleScrollBars() {
        ScrollPane pane = (ScrollPane) canvas.lookup(".centered-scroll-pane");
        return pane.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .filter(javafx.scene.Node::isVisible)
                .count();
    }

    private void show(int classes) throws Exception {
        onFx(() -> {
            canvas = new ArchitectureCanvas();
            new Scene(canvas, WIDTH, HEIGHT);
            canvas.setArchitectureRoot(row(classes));
        });
        layout();
    }

    private void fit() throws Exception {
        onFx(() -> canvas.zoomToFit());
        layout();
    }

    /** Scroll bars come and go with a layout pass of their own. */
    private void layout() throws Exception {
        for (int pass = 0; pass < 4; pass++) {
            onFx(() -> {
                canvas.applyCss();
                canvas.requestLayout();
                canvas.layout();
            });
        }
    }

    private static ArchitectureNode row(int classes) {
        ArchitectureNode root = new ArchitectureNode("root", "root", ArchitectureNode.NodeType.PACKAGE, true, 0);
        ArchitectureNode pkg = new ArchitectureNode("pkg", "pkg", ArchitectureNode.NodeType.PACKAGE, true, 0);
        for (int i = 0; i < classes; i++) {
            ArchitectureNode node = new ArchitectureNode(
                    "pkg.Class" + i, "Class" + i, ArchitectureNode.NodeType.CLASS, false, 0);
            node.setDependencies(Set.of());
            pkg.addChild(node);
        }
        root.addChild(pkg);
        return root;
    }

    private static void onFx(Runnable action) throws Exception {
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw e;
        }
    }
}
