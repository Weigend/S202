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

import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.analysis.quality.QualityMetrics;
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
    final IntegerProperty packageDepth = new SimpleIntegerProperty(3);
    final BooleanProperty showDependencies = new SimpleBooleanProperty(false);
    final BooleanProperty showScc = new SimpleBooleanProperty(false);
    final BooleanProperty showPackageScc = new SimpleBooleanProperty(false);
    final BooleanProperty showWhatIfViolations = new SimpleBooleanProperty(false);
    final BooleanProperty showTangleDebugLines = new SimpleBooleanProperty(false);
    final ReadOnlyDoubleWrapper zoomFactor = new ReadOnlyDoubleWrapper(1.0);
    final ReadOnlyObjectWrapper<ArchitectureNode> architectureRoot = new ReadOnlyObjectWrapper<>(null);
    final ReadOnlyObjectWrapper<QualityMetrics> qualityMetrics = new ReadOnlyObjectWrapper<>(null);

    private Consumer<String> statusSink = msg -> { /* no-op default */ };

    private String preferredTopTanglesScope;
    private boolean topTanglesScopeOwner = true;
    private boolean skipTransparentTopLevelPackages = true;
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
     * Read-only handle to the current quality metrics for this view, or null
     * if the analysis hasn't computed them yet. Pushed in by the host shell
     * after each successful analysis.
     */
    public ReadOnlyObjectProperty<QualityMetrics> qualityMetricsProperty() {
        return qualityMetrics.getReadOnlyProperty();
    }

    public QualityMetrics getQualityMetrics() {
        return qualityMetrics.get();
    }

    public void setQualityMetrics(QualityMetrics metrics) {
        qualityMetrics.set(metrics);
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

    public BooleanProperty showTangleDebugLinesProperty() {
        return showTangleDebugLines;
    }

    public boolean isShowTangleDebugLines() {
        return showTangleDebugLines.get();
    }

    public void setShowTangleDebugLines(boolean show) {
        showTangleDebugLines.set(show);
    }

    /* ----- View-Konfiguration --------------------------------------------------- */

    public String getPreferredTopTanglesScope() {
        return preferredTopTanglesScope;
    }

    public void setPreferredTopTanglesScope(String scope) {
        preferredTopTanglesScope = scope == null || scope.isBlank() ? null : scope;
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
     * Whether this view drives TopTangles scope tracking. True for all regular
     * architecture and scope views; false for tangle satellite tabs, which
     * inherit their scope from the source view and must not reset it on focus.
     */
    public boolean isTopTanglesScopeOwner() {
        return topTanglesScopeOwner;
    }

    public void setTopTanglesScopeOwner(boolean owner) {
        topTanglesScopeOwner = owner;
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
