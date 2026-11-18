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
package de.weigend.s202.ui.shell;

import io.softwareecg.wfx.windowmanager.api.WindowManager;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was eine Sitzung über Hauptfenster und View-Anordnung hinterlässt, findet
 * die nächste wieder — und ein unbrauchbarer Rest hält den Start nicht auf.
 */
class SessionLayoutTest {

    private static boolean javaFxStarted;

    @TempDir
    Path settings;

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        // On its own daemon thread: without a display the toolkit never comes
        // up, and once an earlier test class has tried to start it, runLater
        // blocks for good. The wait below then times out and the tests skip.
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    ready.countDown();
                });
            } catch (IllegalStateException alreadyStarted) {
                Platform.runLater(ready::countDown);
            }
        }, "javafx-test-starter");
        starter.setDaemon(true);
        starter.start();
        javaFxStarted = ready.await(20, TimeUnit.SECONDS);
    }

    @Test
    void theWindowComesBackWhereItWas() {
        Rectangle2D screen = onFx(() -> Screen.getPrimary().getVisualBounds());
        double x = screen.getMinX() + 40;
        double y = screen.getMinY() + 30;

        onFx(() -> {
            Stage left = new Stage();
            left.setX(x);
            left.setY(y);
            left.setWidth(1100);
            left.setHeight(800);
            new SessionLayout(settings).save(left, windowManager("the layout", null));
            return null;
        });

        Stage next = onFx(() -> {
            Stage stage = new Stage();
            new SessionLayout(settings).restoreWindow(stage);
            return stage;
        });
        assertEquals(x, next.getX(), 0.5);
        assertEquals(y, next.getY(), 0.5);
        assertEquals(1100, next.getWidth(), 0.5);
        assertEquals(800, next.getHeight(), 0.5);
        assertFalse(next.isMaximized());
    }

    @Test
    void aMaximizedWindowComesBackMaximizedAndRemembersItsNormalSize() {
        Rectangle2D screen = onFx(() -> Screen.getPrimary().getVisualBounds());
        onFx(() -> {
            Stage normal = new Stage();
            normal.setX(screen.getMinX() + 10);
            normal.setY(screen.getMinY() + 10);
            normal.setWidth(1200);
            normal.setHeight(900);
            new SessionLayout(settings).save(normal, windowManager("", null));

            Stage maximized = new Stage();
            maximized.setWidth(3000);       // whatever the maximized window reports
            maximized.setMaximized(true);
            new SessionLayout(settings).save(maximized, windowManager("", null));
            return null;
        });

        Stage next = onFx(() -> {
            Stage stage = new Stage();
            new SessionLayout(settings).restoreWindow(stage);
            return stage;
        });
        assertTrue(next.isMaximized());
        assertEquals(1200, next.getWidth(), 0.5, "the size to go back to when it is no longer maximized");
    }

    @Test
    void aWindowThatWouldBeOffEveryScreenStaysWhereThePlatformPutsIt() throws Exception {
        Files.writeString(settings.resolve("window.properties"),
                "x=-50000\ny=-50000\nwidth=800\nheight=600\nmaximized=false\n");

        Stage next = onFx(() -> {
            Stage stage = new Stage();
            new SessionLayout(settings).restoreWindow(stage);
            return stage;
        });

        assertTrue(Double.isNaN(next.getX()), "not moved");
    }

    @Test
    void theArrangementOfTheViewsIsHandedBackToTheWindowManager() {
        AtomicReference<String> restored = new AtomicReference<>();
        onFx(() -> {
            new SessionLayout(settings).save(null, windowManager("main.divider=0.3\nfocused=outline\n", null));
            new SessionLayout(settings).restoreViews(windowManager(null, restored));
            return null;
        });

        assertEquals("main.divider=0.3\nfocused=outline\n", restored.get());
    }

    @Test
    void theFirstStartAndABrokenFileAreNoObstacle() throws Exception {
        AtomicReference<String> restored = new AtomicReference<>();
        Stage untouched = onFx(() -> {
            Stage stage = new Stage();
            SessionLayout nothingSaved = new SessionLayout(settings.resolve("not-there-yet"));
            nothingSaved.restoreWindow(stage);
            nothingSaved.restoreViews(windowManager(null, restored));
            return stage;
        });
        assertTrue(Double.isNaN(untouched.getX()));
        assertNull(restored.get(), "nothing to restore, nobody asked");

        Files.writeString(settings.resolve("window.properties"), "x=not a number\nmaximized=perhaps\n");
        Stage afterBrokenFile = onFx(() -> {
            Stage stage = new Stage();
            new SessionLayout(settings).restoreWindow(stage);
            return stage;
        });
        assertTrue(Double.isNaN(afterBrokenFile.getX()));
        assertFalse(afterBrokenFile.isMaximized());
    }

    /** A window manager that answers {@code saveLayout()} and records what {@code restoreLayout} gets. */
    private static WindowManager windowManager(String layout, AtomicReference<String> restored) {
        return (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                new Class<?>[] {WindowManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "saveLayout" -> layout;
                    case "restoreLayout" -> {
                        restored.set((String) args[0]);
                        yield true;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) {
        Assumptions.assumeTrue(javaFxStarted, "JavaFX toolkit is unavailable");
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw new AssertionError(e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
