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
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.core.arrows.LineColors;
import de.weigend.s202.ui.core.graph.BoxDroppedEvent;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.core.matrix.MatrixView;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.core.model.WhatIfMove;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.QuadCurve;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A view shows the analyzed code or the plan, and everything in it the same:
 * the order of the layers, violations, tangles and the matrix. What the user
 * planned and how the picture is set up survives every rebuild.
 */
class PlanAndMatrixTest {

    private static final Duration FX_TIMEOUT = Duration.ofSeconds(20);
    private static final DependencyEdge UP = new DependencyEdge("com.shop.base.Audit", "com.shop.order.Order");

    private static boolean javaFxStarted;

    private DomainModel model;
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
            model = new LevelCalculator().calculate(raw);
            canvas = new ArchitectureCanvas();
            canvas.setRawDependencyModel(raw);
            canvas.setDomainModel(model);
            canvas.setCycleBreakEdges(model.getClassBackEdges());
            canvas.setArchitectureRoot(new ArchitectureNodeBuilder().build(model));
            new Scene(canvas, 900, 700);
            canvas.applyCss();
            canvas.layout();
        });
    }

    /* ----- The plan ------------------------------------------------------------- */

    @Test
    void aCutCountsInEverythingThePlanShows() {
        onFx(() -> {
            assertEquals(1, canvas.getWhatIfArchitecture().violations().size());

            canvas.applyEdgeCut(UP.from(), UP.to());

            assertTrue(canvas.getWhatIfArchitecture().violations().isEmpty());
            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty());
            assertFalse(canvas.getShownModel().getClass(UP.from()).dependencies.contains(UP.to()));
            assertEquals(Set.of(UP), canvas.getPlan().cuts());
            assertEquals(1, canvas.getPlan().planned());
            assertSame(model, canvas.getDomainModel(), "the analyzed model stays");
        });
    }

    @Test
    void withoutThePlanItIsTheAnalyzedCode() {
        onFx(() -> {
            canvas.applyEdgeCut(UP.from(), UP.to());
            moveAuditToOrder();

            canvas.setShowPlan(false);

            assertSame(model, canvas.getShownModel());
            assertEquals(1, canvas.getWhatIfArchitecture().violations().size());
            assertEquals("com.shop.base", packageBoxOf("com.shop.base.Audit"));
            assertTrue(canvas.getPlan().cuts().isEmpty());
            assertTrue(canvas.getPlan().placement().isOfTheCode());
            assertEquals(2, canvas.getPlan().planned(), "the plan is kept");

            canvas.setShowPlan(true);

            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty());
            assertEquals(List.of("com.shop.base.Audit"), canvas.getWhatIfArchitecture().violations().stream()
                    .map(v -> v.targetFqn()).toList(), "it was put above Order, which uses it");
            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
            assertEquals("com.shop.order", canvas.getPlan().placement().containerOf("com.shop.base.Audit"));
        });
    }

    @Test
    void aCutOfAnotherViewLeavesTheAnalyzedCode() {
        onFx(() -> {
            canvas.setShowPlan(false);
            canvas.applyEdgeCut(UP.from(), UP.to());
            assertFalse(canvas.isShowPlan(), "a cut made in another view does not switch this one");
            assertEquals(1, canvas.getWhatIfArchitecture().violations().size());
            assertEquals(1, canvas.getPlan().planned());
        });
    }

    @Test
    void aFirstMoveInTheAnalyzedCodeShowsThePlan() {
        onFx(() -> {
            canvas.setShowPlan(false);

            moveAuditToOrder();

            assertTrue(canvas.isShowPlan());
            assertEquals(1, canvas.getWhatIfMoves().size());
            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
        });
    }

    @Test
    void theAnalyzedCodeIsNotMovedWhileThereAreMoves() {
        onFx(() -> {
            moveAuditToOrder();
            canvas.setShowPlan(false);

            drop("com.shop.base.Money", "com.shop.ui");

            assertEquals(1, canvas.getWhatIfMoves().size(), "where it was dropped means something else in the plan");
            assertEquals("com.shop.base", packageBoxOf("com.shop.base.Money"));
        });
    }

    @Test
    void theAnnotationsOfAnotherViewLeaveTheMoves() {
        onFx(() -> {
            moveAuditToOrder();
            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty(), "Audit is in order now");

            canvas.setArchitectureAnnotations(
                    canvas.getArchitectureAnnotations().withComponentApiIncluded("com.shop.order.Order"));

            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty());
        });
    }

    @Test
    void refreshKeepsMovesAndZoom() {
        onFx(() -> {
            moveAuditToOrder();
            canvas.setZoom(0.5);

            canvas.refreshLayout();

            assertEquals(1, canvas.getWhatIfMoves().size());
            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty());
            assertEquals(0.5, canvas.zoomFactorProperty().get(), 0.001);
        });
    }

    @Test
    void undoKeepsZoomAndClosedPackages() {
        onFx(() -> {
            moveAuditToOrder();
            canvas.setZoom(0.5);
            box(LevelPackageBox.class, b -> "com.shop.ui".equals(b.getFullName())).setExpanded(false);

            canvas.undoWhatIf();

            assertEquals("com.shop.base", packageBoxOf("com.shop.base.Audit"));
            assertEquals(1, canvas.getWhatIfArchitecture().tangles().size());
            assertEquals(0.5, canvas.zoomFactorProperty().get(), 0.001);
            assertFalse(box(LevelPackageBox.class, b -> "com.shop.ui".equals(b.getFullName())).isExpanded());

            canvas.redoWhatIf();

            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty());
        });
    }

    /** Add to Scope and the context of a tangle show another excerpt of the same code. */
    @Test
    void anotherExcerptKeepsTheMoves() {
        onFx(() -> {
            moveAuditToOrder();

            canvas.setArchitectureRoot(new ArchitectureNodeBuilder().build(model));

            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
        });
    }

    @Test
    void discardingTheMovesLeavesTheCuts() {
        onFx(() -> {
            moveAuditToOrder();
            canvas.applyEdgeCut(UP.from(), UP.to());

            canvas.discardMoves();

            assertTrue(canvas.getWhatIfMoves().isEmpty());
            assertEquals("com.shop.base", packageBoxOf("com.shop.base.Audit"));
            assertEquals(Set.of(UP), canvas.getPlan().cuts());
        });
    }

    @Test
    void aRestoredPlanIsShownAndCanBeUndone() {
        onFx(() -> {
            canvas.restorePlan(List.of(new WhatIfMove.AsNewRow("com.shop.base.Audit", "com.shop.order", 0, false)),
                    Map.of(), true);

            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
            assertTrue(canvas.getWhatIfArchitecture().tangles().isEmpty());

            canvas.undoWhatIf();

            assertEquals("com.shop.base", packageBoxOf("com.shop.base.Audit"));
        });
    }

    @Test
    void theComponentViewWithoutThePlanHasNoMoves() {
        onFx(() -> {
            canvas.setViewStyle(ArchitectureKind.COMPONENT);
            canvas.restorePlan(List.of(new WhatIfMove.AsNewRow("com.shop.base.Audit", "com.shop.order", 0, false)),
                    Map.of("virtual-pkg-1", "refund"), true);
            assertEquals("com.shop.order", packageBoxOf("com.shop.base.Audit"));
            assertEquals(Map.of("virtual-pkg-1", "refund"), canvas.getPlannedPackages());
            assertEquals(2, canvas.getPlan().planned());

            canvas.setShowPlan(false);

            assertEquals("com.shop.base", packageBoxOf("com.shop.base.Audit"));
            assertEquals(1, canvas.getPlannedPackages().size(), "the plan is kept");
        });
    }

    /* ----- Lines ------------------------------------------------------------------ */

    @Test
    void aViolationIsRedAndCanBeSelected() {
        List<DependencySelection> selections = new ArrayList<>();
        onFx(() -> {
            canvas.setOnDependencySelected(selections::add);
            canvas.setShowWhatIfViolations(true);
        });
        await(() -> curves().size() == 2);
        onFx(() -> {
            QuadCurve line = curves().stream().filter(c -> LineColors.AGAINST_LAYERS.equals(c.getStroke()))
                    .findFirst().orElseThrow();
            assertTrue(line.getStrokeDashArray().isEmpty(), "in the code, so solid");

            line.getOnMouseClicked().handle(click());

            assertEquals(LineColors.SELECTED, line.getStroke());
            assertEquals(List.of(new DependencySelection(UP.from(), UP.to(), List.of(UP))), selections);

            line.getOnMouseClicked().handle(click());

            assertEquals(LineColors.AGAINST_LAYERS, line.getStroke());
            assertEquals(DependencySelection.NONE, selections.get(1));
        });
    }

    @Test
    void theSelectedLineIsSelectedAgainAfterARedraw() {
        onFx(() -> canvas.setShowWhatIfViolations(true));
        await(() -> curves().size() == 2);
        long[] before = new long[1];
        onFx(() -> {
            curves().get(1).getOnMouseClicked().handle(click());
            before[0] = canvas.redrawTickProperty().getValue().longValue();
            canvas.redrawDependencyArrows();
        });
        await(() -> canvas.redrawTickProperty().getValue().longValue() > before[0]);

        onFx(() -> assertTrue(curves().stream().anyMatch(c -> LineColors.SELECTED.equals(c.getStroke()))));
    }

    @Test
    void theBoxUnderTheMouseLetsItsArrowsStandOut() {
        onFx(() -> canvas.setShowDependencies(true));
        long[] before = new long[1];
        await(() -> canvas.redrawTickProperty().getValue().longValue() > 0);
        onFx(() -> {
            LevelClassBox money = box(LevelClassBox.class, b -> "com.shop.base.Money".equals(b.getFullName()));
            LevelClassBox line = box(LevelClassBox.class, b -> "com.shop.order.Line".equals(b.getFullName()));
            LevelClassBox audit = box(LevelClassBox.class, b -> "com.shop.base.Audit".equals(b.getFullName()));

            Event.fireEvent(money, mouse(MouseEvent.MOUSE_MOVED, money));

            assertTrue(line.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("connected")));
            assertFalse(audit.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("connected")));

            Event.fireEvent(audit, mouse(MouseEvent.MOUSE_MOVED, audit));

            assertFalse(line.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("connected")));
        });
    }

    /* ----- The matrix ---------------------------------------------------------------- */

    @Test
    void theMatrixShowsWhatThePictureShows() {
        onFx(() -> {
            MatrixView matrix = openMatrix();
            assertEquals(3, matrix.matrix().size());
            assertEquals(1, matrix.matrix().tangles());

            canvas.applyEdgeCut(UP.from(), UP.to());
        });
        await(() -> canvas.getMatrix().matrix().tangles() == 0);
        onFx(() -> canvas.setShowPlan(false));
        await(() -> canvas.getMatrix().matrix().tangles() == 1);
    }

    @Test
    void theMatrixFollowsAMove() {
        onFx(() -> {
            moveAuditToOrder();
            MatrixView matrix = openMatrix();
            matrix.setOpen(Set.of("com.shop.order"));

            assertEquals(1, matrix.matrix().tangles(), "Order and Audit are one tangle inside order");
            assertTrue(matrix.matrix().items().stream().anyMatch(i -> i.fqn().equals("com.shop.base.Audit")));
        });
    }

    @Test
    void theMatrixIsNotMadeWhileNobodyLooksAtIt() {
        onFx(() -> {
            canvas.getMatrix().setOpen(Set.of("com.shop.order"));
            assertNull(canvas.getMatrix().matrix());

            assertEquals(4, openMatrix().matrix().size(), "the open packages are known when it is shown");
        });
    }

    @Test
    void pictureAndMatrixHaveAZoomEach() {
        onFx(() -> {
            MatrixView matrix = openMatrix();
            canvas.setZoom(0.5);
            matrix.zoomIn();

            assertEquals(0.5, canvas.zoomFactorProperty().get(), 0.001);
            assertTrue(matrix.zoomProperty().get() > 1.0);
        });
    }

    @Test
    void onlyLayersHaveAMatrix() {
        onFx(() -> {
            assertTrue(canvas.hasMatrix());
            canvas.setViewStyle(ArchitectureKind.COMPONENT);
            assertFalse(canvas.hasMatrix());
        });
    }

    @Test
    void aPictureOfTheMatrixIsTheWholeOfIt() {
        onFx(() -> assertTrue(openMatrix().picture().getLayoutBounds().getWidth() > 100));
    }

    /* ----- Helpers --------------------------------------------------------------------- */

    private MatrixView openMatrix() {
        new Scene(canvas.getMatrix(), 600, 600);
        return canvas.getMatrix();
    }

    private void moveAuditToOrder() {
        drop("com.shop.base.Audit", "com.shop.order");
        assertNotNull(canvas.getWhatIfMoves());
    }

    private void drop(String element, String container) {
        Node node = box(LevelClassBox.class, b -> element.equals(b.getFullName()));
        VBox stack = box(LevelPackageBox.class, b -> container.equals(b.getFullName())).rowStack(false);
        HBox source = (HBox) node.getParent();
        source.getChildren().remove(node);
        if (source.getChildren().isEmpty()) {
            ((VBox) source.getParent()).getChildren().remove(source);
        }
        HBox row = new HBox(node);
        stack.getChildren().add(0, row);
        node.fireEvent(new BoxDroppedEvent(node, row, true));
    }

    private String packageBoxOf(String element) {
        Node node = box(LevelClassBox.class, b -> element.equals(b.getFullName()));
        for (Parent p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof de.weigend.s202.ui.core.graph.ContainerBox box && box.getFullName() != null) {
                return box.getFullName();
            }
        }
        return null;
    }

    private List<QuadCurve> curves() {
        List<QuadCurve> result = new ArrayList<>();
        collect(canvas, QuadCurve.class, c -> true, result);
        return result;
    }

    private <T> T box(Class<T> type, Predicate<T> wanted) {
        List<T> result = new ArrayList<>();
        collect(canvas, type, wanted, result);
        assertFalse(result.isEmpty(), type.getSimpleName() + " not found");
        return result.get(0);
    }

    private static <T> void collect(Node node, Class<T> type, Predicate<T> wanted, List<T> result) {
        if (type.isInstance(node) && wanted.test(type.cast(node))) {
            result.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, wanted, result));
        }
    }

    private static MouseEvent click() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, true, null);
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, Node target) {
        return new MouseEvent(target, target, type, 1, 1, 1, 1, MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, true, new PickResult(target, 1, 1));
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

    /** ui uses order, order uses base, and base - the mistake - uses order back. */
    private static DependencyModel shop() {
        DependencyModel raw = new DependencyModel();
        add(raw, "com.shop.ui.Screen", "com.shop.order.Order");
        add(raw, "com.shop.order.Order", "com.shop.order.Line", "com.shop.base.Money", "com.shop.base.Audit");
        raw.getClass("com.shop.order.Order").addMethod("run", "()V");
        raw.getClass("com.shop.order.Order").getMethod("run", "()V").methodCalls.put("com.shop.base.Money.go", 5);
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
}
