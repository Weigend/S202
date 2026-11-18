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

import de.weigend.s202.ui.core.graph.ElementLookup;
import de.weigend.s202.ui.views.component.ComponentArchitectureTreeBuilder;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.impl.ComponentArchitectureModel;
import de.weigend.s202.domain.architecture.Element;
import io.softwareecg.wfx.lookup.api.Lookup;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.ui.core.graph.LevelClassBox;
import de.weigend.s202.ui.core.graph.LevelPackageBox;
import de.weigend.s202.ui.views.component.ComponentBox;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ArchitectureTreeBuilderTest {

    private static final Duration FX_TIMEOUT = Duration.ofSeconds(10);
    private static boolean javaFxStarted;
    private static Throwable javaFxStartupFailure;

    @BeforeAll
    static void initJavaFX() {
        Lookup.init();
        startJavaFx();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    // The JavaFX toolkit is deliberately left running: it can be started only
    // once per JVM, and the test classes that run after this one in the same
    // Surefire fork need it too. Shutting it down here made them skip all
    // their tests. The fork ends the toolkit together with the JVM.

    private static boolean startJavaFx() {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    ready.countDown();
                });
            } catch (IllegalStateException alreadyStarted) {
                try {
                    Platform.runLater(ready::countDown);
                } catch (IllegalStateException e) {
                    failure.set(e);
                    ready.countDown();
                }
            } catch (Throwable t) {
                failure.set(t);
                ready.countDown();
            }
        }, "javafx-test-starter");
        starter.setDaemon(true);
        starter.start();

        boolean started;
        try {
            started = ready.await(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!started) {
            return false;
        }
        if (failure.get() != null) {
            javaFxStartupFailure = failure.get();
            return false;
        }
        javaFxStarted = true;
        return true;
    }

    private static void runOnFxThread(FxAssertion assertion) {
        Assumptions.assumeTrue(javaFxStarted, () -> javaFxStartupFailure == null
                ? "JavaFX toolkit did not start within " + FX_TIMEOUT
                : "JavaFX toolkit is unavailable: " + javaFxStartupFailure.getMessage());
        if (Platform.isFxApplicationThread()) {
            assertion.run();
            return;
        }
        FutureTask<Void> task = new FutureTask<>(() -> {
            assertion.run();
            return null;
        });
        Platform.runLater(task);
        try {
            task.get(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for JavaFX test execution", e);
        } catch (TimeoutException e) {
            fail("Timed out while waiting for JavaFX test execution", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AssertionError assertionError) {
                throw assertionError;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(cause);
        }
    }

    @FunctionalInterface
    private interface FxAssertion {
        void run();
    }

    /**
     * Top-level packages at the same level must be placed side-by-side (same HBox),
     * not stacked vertically (separate rows).
     */
    @Test
    public void testSameLevelTopPackagesAreSideBySide() {
        runOnFxThread(() -> {
            // Build tree: root -> analysis(L:0), reader(L:0), domain(L:1), ui(L:2)
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            root.addChild(new ArchitectureNode("analysis", "analysis", NodeType.PACKAGE, true, 0));
            root.addChild(new ArchitectureNode("reader", "reader", NodeType.PACKAGE, true, 0));
            root.addChild(new ArchitectureNode("domain", "domain", NodeType.PACKAGE, true, 1));
            root.addChild(new ArchitectureNode("ui", "ui", NodeType.PACKAGE, true, 2));

            Map<String, Node> registry = new HashMap<>();
            ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
            VBox topLevel = builder.buildTree(root);

            // topLevel should have 3 HBox rows: L2, L1, L0
            List<HBox> rows = rows(topLevel);
            assertEquals(3, rows.size(), "Should have 3 level rows (L2, L1, L0)");

            // Row 0 (L:2): ui alone
            assertEquals(1, rows.get(0).getChildren().size(), "L2 row should have 1 package (ui)");

            // Row 1 (L:1): domain alone
            assertEquals(1, rows.get(1).getChildren().size(), "L1 row should have 1 package (domain)");

            // Row 2 (L:0): analysis and reader side-by-side
            assertEquals(2, rows.get(2).getChildren().size(),
                    "L0 row should have 2 packages (analysis and reader) side-by-side");
        });
    }

    /**
     * When all top-level packages have the same level, they must all be in one HBox.
     */
    @Test
    public void testAllSameLevelInOneRow() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            root.addChild(new ArchitectureNode("a", "a", NodeType.PACKAGE, true, 0));
            root.addChild(new ArchitectureNode("b", "b", NodeType.PACKAGE, true, 0));
            root.addChild(new ArchitectureNode("c", "c", NodeType.PACKAGE, true, 0));

            Map<String, Node> registry = new HashMap<>();
            ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
            VBox topLevel = builder.buildTree(root);

            List<HBox> rows = rows(topLevel);
            assertEquals(1, rows.size(), "All same-level packages should be in 1 row");
            assertEquals(3, rows.get(0).getChildren().size(), "The single row should contain all 3 packages");
        });
    }

    /**
     * Each distinct level gets its own row, ordered descending (highest level at top).
     */
    @Test
    public void testDistinctLevelsGetSeparateRows() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            root.addChild(new ArchitectureNode("low", "low", NodeType.PACKAGE, true, 0));
            root.addChild(new ArchitectureNode("mid", "mid", NodeType.PACKAGE, true, 1));
            root.addChild(new ArchitectureNode("high", "high", NodeType.PACKAGE, true, 2));

            Map<String, Node> registry = new HashMap<>();
            ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
            VBox topLevel = builder.buildTree(root);

            List<HBox> rows = rows(topLevel);
            assertEquals(3, rows.size(), "3 distinct levels should produce 3 rows");

            // Each row should have exactly 1 package
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(1, rows.get(i).getChildren().size(),
                        "Row " + i + " should have exactly 1 package");
            }
        });
    }

    @Test
    public void testTopLevelPaddingReservesOuterTangleLanes() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            root.addChild(new ArchitectureNode("ui", "ui", NodeType.PACKAGE, true, 2));

            Map<String, Node> registry = new HashMap<>();
            ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
            VBox topLevel = builder.buildTree(root);

            assertEquals(52.0, topLevel.getPadding().getTop(), 0.0001,
                    "Top gap should fit seven tangle lanes");
            assertEquals(52.0, topLevel.getPadding().getBottom(), 0.0001,
                    "Bottom gap should fit seven tangle lanes");
            assertEquals(60.0, topLevel.getPadding().getLeft(), 0.0001);
            assertEquals(60.0, topLevel.getPadding().getRight(), 0.0001);
        });
    }

    @Test
    public void testScopeRootPackageCanRemainVisible() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            ArchitectureNode scope = new ArchitectureNode("com.example", "example", NodeType.PACKAGE, true, 1);
            scope.addChild(new ArchitectureNode("com.example.internal", "internal", NodeType.PACKAGE, true, 0));
            root.addChild(scope);

            Map<String, Node> registry = new HashMap<>();
            ArchitectureTreeBuilder builder = new ArchitectureTreeBuilder(registry);
            VBox topLevel = builder.buildTree(root, 3, false);

            List<HBox> rows = rows(topLevel);

            assertEquals(1, rows.size(), "scope root should be the single top-level row");
            assertEquals(1, rows.get(0).getChildren().size(), "scope root should be the visible top-level package");
            assertTrue(rows.get(0).getChildren().get(0) instanceof LevelPackageBox,
                    "scope root should render as a package box, not be skipped as transparent");
            assertSame(rows.get(0).getChildren().get(0), registry.get("com.example"),
                    "registry should point the scope package at its visible box");
        });
    }

    @Test
    public void testComponentApiRendersSelectedPackageHierarchyWithRecalculatedLevels() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            ArchitectureNode com = new ArchitectureNode("com", "com", NodeType.PACKAGE, true, 0);
            ArchitectureNode acme = new ArchitectureNode("com.acme", "acme", NodeType.PACKAGE, true, 0);
            ArchitectureNode payment = new ArchitectureNode(
                    "com.acme.payment", "payment", NodeType.PACKAGE, true, 0);
            ArchitectureNode contract = new ArchitectureNode(
                    "com.acme.payment.contract", "contract", NodeType.PACKAGE, true, 7);
            ArchitectureNode high = new ArchitectureNode(
                    "com.acme.payment.contract.High", "High", NodeType.CLASS, true, 7);
            ArchitectureNode low = new ArchitectureNode(
                    "com.acme.payment.contract.Low", "Low", NodeType.CLASS, true, 7);
            ArchitectureNode implementation = new ArchitectureNode(
                    "com.acme.payment.PaymentService", "PaymentService", NodeType.CLASS, true, 0);
            ArchitectureNode shipping = new ArchitectureNode(
                    "com.acme.shipping", "shipping", NodeType.PACKAGE, true, 0);
            ArchitectureNode shippingService = new ArchitectureNode(
                    "com.acme.shipping.ShippingService", "ShippingService", NodeType.CLASS, true, 0);

            high.setDependencies(Set.of(low.getFullName()));
            contract.addChild(high);
            contract.addChild(low);
            payment.addChild(contract);
            payment.addChild(implementation);
            shipping.addChild(shippingService);
            acme.addChild(payment);
            acme.addChild(shipping);
            com.addChild(acme);
            root.addChild(com);

            Map<String, Node> registry = new HashMap<>();
            ComponentArchitectureTreeBuilder builder = new ComponentArchitectureTreeBuilder(
                    registry,
                    null,
                    ArchitectureAnnotations.empty().withComponentApiIncluded(contract.getFullName()),
                    null);
            builder.buildTree(root, 3);

            Node contractBox = registry.get(contract.getFullName());
            Node highBox = registry.get(high.getFullName());
            Node lowBox = registry.get(low.getFullName());

            assertInstanceOf(LevelPackageBox.class, contractBox);
            assertInstanceOf(LevelClassBox.class, highBox);
            assertInstanceOf(LevelClassBox.class, lowBox);
            assertTrue(ComponentBox.isApiElement(contractBox), "API package must remain draggable out of API");
            assertTrue(ComponentBox.isApiElement(highBox));
            assertTrue(isDescendantOf(highBox, contractBox), "API class must stay nested below its package");
            assertTrue(isDescendantOf(lowBox, contractBox), "API class must stay nested below its package");
            assertTrue(((LevelClassBox) highBox).getText().contains("L:1"));
            assertTrue(((LevelClassBox) lowBox).getText().contains("L:0"));
        });
    }

    @Test
    public void testComponentProjectionRefreshPreservesViewportAndExpansionState() {
        runOnFxThread(() -> {
            ArchitectureNode root = componentSourceRoot();
            ArchitectureCanvas view = new ArchitectureCanvas();
            view.setViewStyle(ArchitectureKind.COMPONENT);
            view.setArchitectureRoot(root);

            ComponentBox component = findComponent(view, "com.acme.payment");
            LevelPackageBox apiPackage = findPackage(view, "com.acme.payment.api", true);
            LevelPackageBox internalPackage = findPackage(view, "com.acme.payment.internal", false);
            LevelPackageBox billingPackage = findPackage(view, "com.acme.billing", false);
            ScrollPane scrollPane = findNode(view, ScrollPane.class);

            apiPackage.setExpanded(false);
            internalPackage.setExpanded(false);
            billingPackage.setExpanded(false);
            component.setApiExpanded(false);
            component.setExpanded(false);
            view.setZoom(0.6);
            scrollPane.setHvalue(0.37);
            scrollPane.setVvalue(0.61);

            view.setArchitectureAnnotations(ArchitectureAnnotations.empty()
                    .withComponentApiIncluded("com.acme.payment.internal")
                    .withComponentApiIncluded("com.acme.billing.BillingService"));
            view.refreshStyleProjection();

            ComponentBox refreshedComponent = findComponent(view, "com.acme.payment");
            ComponentBox newBillingComponent = findComponent(view, "com.acme.billing");
            LevelPackageBox refreshedApiPackage = findPackage(view, "com.acme.payment.api", true);
            LevelPackageBox movedInternalPackage = findPackage(view, "com.acme.payment.internal", true);
            ScrollPane refreshedScrollPane = findNode(view, ScrollPane.class);

            assertFalse(refreshedComponent.isExpanded());
            assertFalse(refreshedComponent.isApiExpanded());
            assertFalse(newBillingComponent.isExpanded(),
                    "A package that becomes a component must keep its expansion state");
            assertFalse(refreshedApiPackage.isExpanded());
            assertFalse(movedInternalPackage.isExpanded(),
                    "Package state must survive moving from implementation to API");
            assertEquals(0.6, view.zoomFactorProperty().get(), 0.0001);
            assertEquals(0.37, refreshedScrollPane.getHvalue(), 0.0001);
            assertEquals(0.61, refreshedScrollPane.getVvalue(), 0.0001);
        });
    }

    @Test
    public void testComponentProjectionKeepsNonComponentPackagesInLevelRows() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            ArchitectureNode payment = new ArchitectureNode(
                    "com.acme.payment", "payment", NodeType.PACKAGE, true, 2);
            ArchitectureNode paymentApi = new ArchitectureNode(
                    "com.acme.payment.PaymentApi", "PaymentApi", NodeType.CLASS, true, 2, true);
            ArchitectureNode shipping = new ArchitectureNode(
                    "com.acme.shipping", "shipping", NodeType.PACKAGE, true, 0);
            ArchitectureNode shippingService = new ArchitectureNode(
                    "com.acme.shipping.ShippingService", "ShippingService", NodeType.CLASS, true, 0);
            payment.addChild(paymentApi);
            shipping.addChild(shippingService);
            root.addChild(payment);
            root.addChild(shipping);

            ComponentArchitecture architecture = new ComponentArchitectureModel(
                    List.of(new ComponentArchitecture.ComponentElement(
                            "payment",
                            "Payment",
                            payment.getFullName(),
                            List.of(new Element.ClassElement(paymentApi.getFullName(), 2, 0)),
                            List.of())),
                    List.of(),
                    List.of());

            Map<String, Node> registry = new HashMap<>();
            ComponentArchitectureTreeBuilder builder = new ComponentArchitectureTreeBuilder(
                    registry,
                    null,
                    ArchitectureAnnotations.empty(),
                    null,
                    architecture,
                    null);
            VBox topLevel = builder.buildTree(root, 3);

            assertInstanceOf(ComponentBox.class, registry.get(payment.getFullName()));
            assertInstanceOf(LevelPackageBox.class, registry.get(shipping.getFullName()));
            List<HBox> rows = rows(topLevel);
            assertEquals(2, rows.size());
            assertSame(registry.get(payment.getFullName()), rows.get(0).getChildren().get(0));
            assertSame(registry.get(shipping.getFullName()), rows.get(1).getChildren().get(0));
        });
    }

    /**
     * Lazy mode: a package that starts collapsed gets no child boxes until it
     * is expanded, and then only one level at a time.
     */
    @Test
    public void testLazyCollapsedPackageBuildsChildrenOnFirstExpand() {
        runOnFxThread(() -> {
            Map<String, Node> registry = new HashMap<>();
            new ArchitectureTreeBuilder(registry).lazyCollapsedPackages(true).buildTree(lazySourceRoot(), 1);

            LevelPackageBox ab = (LevelPackageBox) registry.get("a.b");
            assertNotNull(ab, "a.b is visible as a collapsed box");
            assertFalse(ab.isExpanded());
            assertFalse(ab.isMaterialized(), "collapsed package must not have built its content");
            assertFalse(registry.containsKey("a.b.Y"), "class inside the collapsed package has no box yet");
            assertFalse(registry.containsKey("a.b.c"));
            assertTrue(registry.containsKey("a.X"), "content of the expanded package is built");

            ab.setExpanded(true);

            assertTrue(ab.isMaterialized());
            assertTrue(registry.get("a.b.Y") instanceof LevelClassBox, "direct class child is built on expand");
            LevelPackageBox abc = (LevelPackageBox) registry.get("a.b.c");
            assertNotNull(abc, "direct sub-package is built on expand");
            assertTrue(isDescendantOf(abc, ab));
            assertFalse(abc.isExpanded(), "sub-package beyond maxDepth starts collapsed");
            assertFalse(abc.isMaterialized(), "and defers its own content again");
            assertFalse(registry.containsKey("a.b.c.Z"));
        });
    }

    /** Expanding everything in lazy mode yields exactly the boxes of the eager build. */
    @Test
    public void testLazyBuildMatchesEagerBuildOnceEverythingIsExpanded() {
        runOnFxThread(() -> {
            Map<String, Node> eager = new HashMap<>();
            new ArchitectureTreeBuilder(eager).buildTree(lazySourceRoot(), 1);

            Map<String, Node> lazy = new HashMap<>();
            new ArchitectureTreeBuilder(lazy).lazyCollapsedPackages(true).buildTree(lazySourceRoot(), 1);
            assertTrue(lazy.size() < eager.size(), "lazy build must create fewer boxes up front");

            boolean expanded = true;
            while (expanded) {
                expanded = false;
                for (Node node : new ArrayList<>(lazy.values())) {
                    if (node instanceof LevelPackageBox box && !box.isExpanded()) {
                        box.setExpanded(true);
                        expanded = true;
                    }
                }
            }
            assertEquals(eager.keySet(), lazy.keySet());
            for (String fqn : eager.keySet()) {
                assertEquals(eager.get(fqn).getClass(), lazy.get(fqn).getClass(), fqn);
            }
        });
    }

    /** The asynchronous build defers the same packages as the synchronous one. */
    @Test
    public void testLazyAsyncBuildDefersCollapsedPackages() {
        runOnFxThread(() -> {
            Map<String, Node> sync = new HashMap<>();
            new ArchitectureTreeBuilder(sync).lazyCollapsedPackages(true).buildTree(lazySourceRoot(), 1);

            Map<String, Node> async = new HashMap<>();
            AtomicReference<VBox> result = new AtomicReference<>();
            new ArchitectureTreeBuilder(async).lazyCollapsedPackages(true).buildTreeAsync(
                    lazySourceRoot(), 1, null, result::set);

            assertNotNull(result.get(), "small trees complete within the first batch");
            assertEquals(sync.keySet(), async.keySet());
            assertFalse(((LevelPackageBox) async.get("a.b")).isMaterialized());
        });
    }

    /** Renderers ask for the collapsed package standing in for an element; nothing gets built. */
    @Test
    public void testPendingContainerNamesCollapsedPackageWithoutBuilding() {
        runOnFxThread(() -> {
            Map<String, Node> registry = new HashMap<>();
            new ArchitectureTreeBuilder(registry).lazyCollapsedPackages(true).buildTree(lazySourceRoot(), 1);
            Node ab = registry.get("a.b");

            assertSame(ab, ElementLookup.pendingContainer(registry, "a.b.Y"));
            assertSame(ab, ElementLookup.pendingContainer(registry, "a.b.c.Z"),
                    "elements of deferred sub-packages roll up to the innermost existing box");
            assertNull(ElementLookup.pendingContainer(registry, "a.X"), "registered elements have no stand-in");
            assertNull(ElementLookup.pendingContainer(registry, "a.b.NotInView"),
                    "names below a collapsed package that are not part of the view stay unknown");
            assertNull(ElementLookup.pendingContainer(registry, "q.Unknown"));
            assertFalse(((LevelPackageBox) ab).isMaterialized(), "lookup must not build anything");
        });
    }

    /** Selection and what-if moves need the element's own box: it is built without expanding anything. */
    @Test
    public void testMaterializeBuildsPathWithoutExpanding() {
        runOnFxThread(() -> {
            Map<String, Node> registry = new HashMap<>();
            new ArchitectureTreeBuilder(registry).lazyCollapsedPackages(true).buildTree(lazySourceRoot(), 1);
            LevelPackageBox ab = (LevelPackageBox) registry.get("a.b");

            Node z = ElementLookup.materialize(registry, "a.b.c.Z");

            assertTrue(z instanceof LevelClassBox);
            assertSame(z, registry.get("a.b.c.Z"));
            LevelPackageBox abc = (LevelPackageBox) registry.get("a.b.c");
            assertTrue(isDescendantOf(z, abc));
            assertFalse(ab.isExpanded(), "materializing must not expand a.b");
            assertFalse(abc.isExpanded(), "materializing must not expand a.b.c");
            assertNull(ElementLookup.materialize(registry, "a.b.NotInView"));
            assertSame(registry.get("a.X"), ElementLookup.materialize(registry, "a.X"));
        });
    }

    /**
     * root -> a (L1) -> X, b -> Y, c -> Z ; z (L0) -> W. Two top-level packages
     * keep {@code a} from being skipped as a transparent passthrough.
     */
    /**
     * A view of plain nodes draws classes and packages as icon and name: no
     * box around a class, a package no wider than its content, and the level
     * rows further apart so that arrows and their weights fit in between.
     */
    @Test
    public void testPlainNodesDropTheBoxesAndSpreadTheLevels() {
        runOnFxThread(() -> {
            ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
            ArchitectureNode pkg = new ArchitectureNode("root.p", "p", NodeType.PACKAGE, true, 1);
            pkg.addChild(new ArchitectureNode("root.p.Inner", "Inner", NodeType.CLASS, true, 0));
            root.addChild(pkg);
            root.addChild(new ArchitectureNode("root.Top", "Top", NodeType.CLASS, true, 0));

            Map<String, Node> registry = new HashMap<>();
            VBox plain = new ArchitectureTreeBuilder(registry).plainNodes(true).buildTree(root, 3, false);

            LevelClassBox top = (LevelClassBox) registry.get("root.Top");
            LevelPackageBox p = (LevelPackageBox) registry.get("root.p");
            assertTrue(top.getStyleClass().contains("plain-node"));
            assertTrue(((LevelClassBox) registry.get("root.p.Inner")).getStyleClass().contains("plain-node"));
            assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, p.getMaxWidth(), "keeps the width of its content");
            assertNull(HBox.getHgrow(p));
            assertTrue(p.getStyle().contains("-fx-background-color: transparent"));
            assertEquals(LevelPackageBox.PLAIN_LEVEL_GAP, plain.getSpacing());
            assertEquals(LevelPackageBox.PLAIN_LEVEL_GAP, p.getContentContainer().getSpacing());

            String opened = p.getStyle();
            p.setExpanded(false);
            assertNotEquals(opened, p.getStyle(), "collapsed, the frame around the content is gone");

            Map<String, Node> boxed = new HashMap<>();
            new ArchitectureTreeBuilder(boxed).buildTree(root, 3, false);
            LevelPackageBox boxedP = (LevelPackageBox) boxed.get("root.p");
            assertFalse(((LevelClassBox) boxed.get("root.Top")).getStyleClass().contains("plain-node"));
            assertEquals(Double.MAX_VALUE, boxedP.getMaxWidth());
            assertEquals(javafx.scene.layout.Priority.ALWAYS, HBox.getHgrow(boxedP));
        });
    }

    private static ArchitectureNode lazySourceRoot() {
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        ArchitectureNode a = new ArchitectureNode("a", "a", NodeType.PACKAGE, true, 1);
        ArchitectureNode ab = new ArchitectureNode("a.b", "b", NodeType.PACKAGE, true, 0);
        ArchitectureNode abc = new ArchitectureNode("a.b.c", "c", NodeType.PACKAGE, true, 0);
        ArchitectureNode z = new ArchitectureNode("z", "z", NodeType.PACKAGE, true, 0);
        root.addChild(a);
        root.addChild(z);
        a.addChild(new ArchitectureNode("a.X", "X", NodeType.CLASS, true, 1));
        a.addChild(ab);
        ab.addChild(new ArchitectureNode("a.b.Y", "Y", NodeType.CLASS, true, 1));
        ab.addChild(abc);
        abc.addChild(new ArchitectureNode("a.b.c.Z", "Z", NodeType.CLASS, true, 0));
        z.addChild(new ArchitectureNode("z.W", "W", NodeType.CLASS, true, 0));
        return root;
    }

    private static ArchitectureNode componentSourceRoot() {
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        ArchitectureNode com = new ArchitectureNode("com", "com", NodeType.PACKAGE, true, 0);
        ArchitectureNode acme = new ArchitectureNode("com.acme", "acme", NodeType.PACKAGE, true, 0);
        ArchitectureNode payment = new ArchitectureNode(
                "com.acme.payment", "payment", NodeType.PACKAGE, true, 0);
        ArchitectureNode api = new ArchitectureNode(
                "com.acme.payment.api", "api", NodeType.PACKAGE, true, 0);
        ArchitectureNode internal = new ArchitectureNode(
                "com.acme.payment.internal", "internal", NodeType.PACKAGE, true, 0);
        ArchitectureNode billing = new ArchitectureNode(
                "com.acme.billing", "billing", NodeType.PACKAGE, true, 0);
        ArchitectureNode shipping = new ArchitectureNode(
                "com.acme.shipping", "shipping", NodeType.PACKAGE, true, 0);

        api.addChild(new ArchitectureNode(
                "com.acme.payment.api.PaymentFacade", "PaymentFacade", NodeType.CLASS, true, 0));
        internal.addChild(new ArchitectureNode(
                "com.acme.payment.internal.PaymentService", "PaymentService", NodeType.CLASS, true, 0));
        billing.addChild(new ArchitectureNode(
                "com.acme.billing.BillingService", "BillingService", NodeType.CLASS, true, 0));
        shipping.addChild(new ArchitectureNode(
                "com.acme.shipping.ShippingService", "ShippingService", NodeType.CLASS, true, 0));
        payment.addChild(api);
        payment.addChild(internal);
        acme.addChild(payment);
        acme.addChild(billing);
        acme.addChild(shipping);
        com.addChild(acme);
        root.addChild(com);
        return root;
    }

    private static ComponentBox findComponent(Node root, String fullName) {
        return findNode(root, ComponentBox.class, component -> fullName.equals(component.getFullName()));
    }

    private static LevelPackageBox findPackage(Node root, String fullName, boolean apiElement) {
        return findNode(root, LevelPackageBox.class, pkg ->
                fullName.equals(pkg.getFullName()) && ComponentBox.isApiElement(pkg) == apiElement);
    }

    private static <T extends Node> T findNode(Node root, Class<T> type) {
        return findNode(root, type, ignored -> true);
    }

    private static <T extends Node> T findNode(Node root,
                                               Class<T> type,
                                               java.util.function.Predicate<T> predicate) {
        if (type.isInstance(root)) {
            T match = type.cast(root);
            if (predicate.test(match)) {
                return match;
            }
        }
        // ScrollPane.getContent() is not exposed via getChildrenUnmodifiable() without a skin.
        if (root instanceof ScrollPane sp && sp.getContent() != null) {
            try {
                return findNode(sp.getContent(), type, predicate);
            } catch (IllegalArgumentException ignored) {}
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                try {
                    return findNode(child, type, predicate);
                } catch (IllegalArgumentException ignored) {
                    // Continue with the next subtree.
                }
            }
        }
        throw new IllegalArgumentException("Missing node of type " + type.getSimpleName());
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {
        Node current = node == null ? null : node.getParent();
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static List<HBox> rows(VBox topLevel) {
        List<HBox> rows = new ArrayList<>();
        for (Node child : topLevel.getChildren()) {
            if (child instanceof HBox hbox) {
                rows.add(hbox);
            }
        }
        return rows;
    }
}
