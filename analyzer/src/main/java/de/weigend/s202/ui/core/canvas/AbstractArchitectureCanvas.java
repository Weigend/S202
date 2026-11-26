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

import de.weigend.s202.ui.core.model.EdgeKindGroup;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.layout.BorderPane;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Die Property- und Konfigurations-Fläche der Architektur-Ansicht: alle
 * extern bindbaren Settings, die Read-Only-Handles auf Root/Metriken, die
 * Status-Senke und die View-Konfiguration. Bewusst von der Orchestrierung
 * in {@link ArchitectureCanvas} getrennt — hier stehen ausschließlich
 * selbsttragende Zustands-Accessors, dort die Verdrahtung der Controller.
 */
public abstract class AbstractArchitectureCanvas extends BorderPane {

    // Externally bindable settings.
    /** The package nesting depth a view shows unless told otherwise. */
    public static final int DEFAULT_PACKAGE_DEPTH = 3;

    final IntegerProperty packageDepth = new SimpleIntegerProperty(DEFAULT_PACKAGE_DEPTH);
    final BooleanProperty showDependencies = new SimpleBooleanProperty(false);
    final BooleanProperty showScc = new SimpleBooleanProperty(false);
    final BooleanProperty showPackageScc = new SimpleBooleanProperty(false);
    final BooleanProperty showWhatIfViolations = new SimpleBooleanProperty(false);
    final BooleanProperty showPlan = new SimpleBooleanProperty(true);
    final BooleanProperty limitBoxWidth = new SimpleBooleanProperty(false);
    final ReadOnlyObjectWrapper<Set<EdgeKindGroup>> hiddenEdgeKinds = new ReadOnlyObjectWrapper<>(Set.of());
    final ReadOnlyDoubleWrapper zoomFactor = new ReadOnlyDoubleWrapper(1.0);
    final ReadOnlyObjectWrapper<ArchitectureNode> architectureRoot = new ReadOnlyObjectWrapper<>(null);

    private Consumer<String> statusSink = msg -> { /* no-op default */ };

    private String preferredScope;
    private final List<String> scopeExtensions = new ArrayList<>();
    private boolean scopeOwner = true;
    private boolean skipTransparentTopLevelPackages = true;
    private boolean plainNodes;
    private ArchitectureNode scopeExtensionSourceRoot;
    ArchitectureKind viewStyle = ArchitectureKind.LAYERED;

    /* ----- Hooks in die Orchestrierung -------------------------------------- */

    /** Läuft nach jedem {@link #setViewStyle(ArchitectureKind)}. */
    abstract void onViewStyleChanged();

    public abstract void selectByFullName(String fullName);

    public abstract void setOnNodeSelected(Consumer<String> sink);

    /* ----- Status sink ------------------------------------------------------- */

    /**
     * Updates the status bar message. Routes through the configured sink so the
     * host shell (e.g. the WFX statusbar) can pick up status changes.
     */
    public void setStatus(String message) {
        Objects.requireNonNull(message, "message cannot be null");
        statusSink.accept(message);
    }

    /**
     * Set a sink that receives every status message produced by this view.
     * Pass null to detach.
     */
    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink != null ? sink : (m -> {});
    }

    /* ----- Read-Only-Handles -------------------------------------------------- */

    /**
     * Read-only handle to the currently displayed architecture root. Fires when
     * a new JAR is loaded or {@link ArchitectureCanvas#refreshLayout()} runs.
     */
    public ReadOnlyObjectProperty<ArchitectureNode> architectureRootProperty() {
        return architectureRoot.getReadOnlyProperty();
    }

    public ArchitectureNode getArchitectureRoot() {
        return architectureRoot.get();
    }

    /**
     * Stable read-only zoom factor (1.0 = 100%). The underlying
     * ZoomController is rebuilt with the view content, but callers keep
     * observing this view-level property.
     */
    public ReadOnlyDoubleProperty zoomFactorProperty() {
        return zoomFactor.getReadOnlyProperty();
    }

    /* ----- Settings properties ------------------------------------------------ */

    public IntegerProperty packageDepthProperty() {
        return packageDepth;
    }

    public int getPackageDepth() {
        return packageDepth.get();
    }

    public void setPackageDepth(int depth) {
        packageDepth.set(depth);
    }

    public BooleanProperty showDependenciesProperty() {
        return showDependencies;
    }

    public boolean isShowDependencies() {
        return showDependencies.get();
    }

    public void setShowDependencies(boolean show) {
        showDependencies.set(show);
    }

    public BooleanProperty showSccProperty() {
        return showScc;
    }

    public boolean isShowScc() {
        return showScc.get();
    }

    public void setShowScc(boolean show) {
        showScc.set(show);
    }

    public BooleanProperty showPackageSccProperty() {
        return showPackageScc;
    }

    public boolean isShowPackageScc() {
        return showPackageScc.get();
    }

    public void setShowPackageScc(boolean show) {
        showPackageScc.set(show);
    }

    public BooleanProperty showWhatIfViolationsProperty() {
        return showWhatIfViolations;
    }

    public boolean isShowWhatIfViolations() {
        return showWhatIfViolations.get();
    }

    public void setShowWhatIfViolations(boolean show) {
        showWhatIfViolations.set(show);
    }

    /**
     * Whether this view shows what is planned - moves, planned packages, cuts -
     * or the code as it was analyzed. The API marks, roles and ports say how
     * the code is to be read and belong to both.
     */
    public BooleanProperty showPlanProperty() {
        return showPlan;
    }

    public boolean isShowPlan() {
        return showPlan.get();
    }

    public void setShowPlan(boolean show) {
        showPlan.set(show);
    }

    /**
     * Whether the package boxes of this view stop growing at a maximum width
     * instead of filling their level row. Off, the boxes end flush with their
     * container, which reads best for a small architecture; on, a wide
     * subtree no longer stretches its neighbours into bars, which a large
     * system needs. Unlike the overlay toggles this is part of how the view
     * is set up and survives a new root.
     */
    public BooleanProperty limitBoxWidthProperty() {
        return limitBoxWidth;
    }

    public boolean isLimitBoxWidth() {
        return limitBoxWidth.get();
    }

    public void setLimitBoxWidth(boolean limit) {
        limitBoxWidth.set(limit);
    }

    /**
     * The kinds of dependency this view does not draw. What is hidden still
     * counts for levels, cycles and violations.
     */
    public Set<EdgeKindGroup> getHiddenEdgeKinds() {
        return hiddenEdgeKinds.get();
    }

    public ReadOnlyObjectProperty<Set<EdgeKindGroup>> hiddenEdgeKindsProperty() {
        return hiddenEdgeKinds.getReadOnlyProperty();
    }

    public void setHiddenEdgeKinds(Set<EdgeKindGroup> hidden) {
        hiddenEdgeKinds.set(hidden == null ? Set.of() : Set.copyOf(hidden));
    }

    /* ----- View-Konfiguration --------------------------------------------------- */

    public String getPreferredScope() {
        return preferredScope;
    }

    public void setPreferredScope(String scope) {
        preferredScope = scope == null || scope.isBlank() ? null : scope;
    }

    /**
     * Regular full-project views hide top-level namespace wrapper packages
     * such as {@code de -> weigend -> s202}. Scoped views disable that skip so
     * the package the user opened from the outline remains visible.
     */
    public void setSkipTransparentTopLevelPackages(boolean skip) {
        skipTransparentTopLevelPackages = skip;
    }

    boolean isSkipTransparentTopLevelPackages() {
        return skipTransparentTopLevelPackages;
    }

    /**
     * Draws packages and classes the way a graph draws its nodes — icon and
     * name, no surrounding box — with the levels further apart. For views
     * that are about the dependencies, not the containment: the tangle view.
     * Takes effect with the next root.
     */
    public void setPlainNodes(boolean plain) {
        plainNodes = plain;
    }

    boolean isPlainNodes() {
        return plainNodes;
    }

    public ArchitectureKind getViewStyle() {
        return viewStyle;
    }

    public void setViewStyle(ArchitectureKind style) {
        viewStyle = style == null ? ArchitectureKind.LAYERED : style;
        onViewStyleChanged();
    }

    /**
     * Enables right-click scope extension for this view. The current root stays
     * filtered, while {@code sourceRoot} remains the complete candidate source.
     */
    public void enableScopeExtensionFrom(ArchitectureNode sourceRoot) {
        scopeExtensionSourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot cannot be null");
        setSkipTransparentTopLevelPackages(false);
    }

    public ArchitectureNode getScopeExtensionSourceRoot() {
        return scopeExtensionSourceRoot;
    }

    /**
     * What "Add to Scope" has added to this scope view, in the order it was
     * added — together with the scope itself this says how the tree of the
     * view came about, so that it can be built again.
     */
    public List<String> getScopeExtensions() {
        return List.copyOf(scopeExtensions);
    }

    /** For a view whose tree was built with these extensions already in it. */
    public void setScopeExtensions(List<String> extensions) {
        scopeExtensions.clear();
        if (extensions != null) {
            scopeExtensions.addAll(extensions);
        }
    }

    void scopeExtended(String fullName) {
        scopeExtensions.add(fullName);
    }

    /**
     * Whether this view drives TopTangles scope tracking. True for all regular
     * architecture and scope views; false for tangle satellite tabs, which
     * inherit their scope from the source view and must not reset it on focus.
     */
    public boolean isScopeOwner() {
        return scopeOwner;
    }

    public void setScopeOwner(boolean owner) {
        scopeOwner = owner;
    }

    /* ----- Deprecated Aliase ------------------------------------------------------ */

    /** @deprecated use {@link #selectByFullName(String)}. */
    @Deprecated
    public void selectClass(String fullClassName) {
        selectByFullName(fullClassName);
    }

    /** @deprecated use {@link #setOnNodeSelected(Consumer)}. */
    @Deprecated
    public void setOnNodeDoubleClicked(Consumer<String> sink) {
        setOnNodeSelected(sink);
    }

    /** @deprecated use {@link #setOnNodeSelected(Consumer)}. */
    @Deprecated
    public void setOnClassDoubleClicked(Consumer<String> sink) {
        setOnNodeSelected(sink);
    }
}
