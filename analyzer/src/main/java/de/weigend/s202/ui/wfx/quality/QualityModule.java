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
package de.weigend.s202.ui.wfx.quality;

import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.outline.OutlineExplorerView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

import java.util.HashSet;
import java.util.Set;

/**
 * WFX module providing the Quality view — a 2D fat-vs-tangled plot rendered
 * below the outline explorer in the LEFT dock area.
 * <p>
 * Tracks {@link WindowManager#focusedViewProperty()}: when an
 * {@link ArchitectureWfxView} gains focus, the plot mirrors that view's
 * metrics. When the user selects a package in the chart, the plot scopes its
 * metrics to that package's classes. In scope views, class selection or no
 * selection falls back to the currently displayed scope; regular views fall
 * back to JAR-level metrics.
 */
@Singleton
@Priority(20)
public class QualityModule implements Module {

    private QualityView qualityView;

    private ArchitectureView boundView;
    private ChangeListener<QualityMetrics> metricsListener;
    private ChangeListener<String> selectionListener;
    private ChangeListener<ArchitectureNode> rootListener;

    @Override
    public String getName() {
        return "Quality";
    }

    @Override
    public void preload() throws PlatformException {
        waitForDemoPreloader();
        qualityView = new QualityView();
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying Quality preload", e);
        }
    }

    @Override
    public void start() {
        WindowManager wm = Lookup.lookup(WindowManager.class);

        // Stack under the outline (vertical split inside the LEFT region) when
        // it's available; fall back to a plain LEFT registration otherwise.
        View outline = wm.findView(OutlineExplorerView.VIEW_ID);
        if (outline != null) {
            wm.register(qualityView, outline);
        } else {
            wm.register(qualityView);
        }

        wm.focusedViewProperty().addListener((obs, was, isNow) -> rebindToFocusedView());
        rebindToFocusedView();
    }

    @Override
    public void stop() {
        unbind();
    }

    private void rebindToFocusedView() {
        ArchitectureWfxView focused = focusedArchitectureView();
        ArchitectureView newBound = focused == null ? null : focused.getArchitectureView();

        // Same fix as OutlineExplorerModule: focus moving onto a side panel
        // shouldn't trigger a re-bind to the same chart.
        if (newBound == boundView) {
            return;
        }

        unbind();

        if (newBound == null) {
            qualityView.setMetrics(null, null);
            return;
        }

        boundView = newBound;
        applyCurrentScope();

        metricsListener = (obs, was, isNow) -> applyCurrentScope();
        selectionListener = (obs, was, isNow) -> applyCurrentScope();
        rootListener = (obs, was, isNow) -> applyCurrentScope();
        newBound.qualityMetricsProperty().addListener(metricsListener);
        newBound.selectedFullNameProperty().addListener(selectionListener);
        newBound.architectureRootProperty().addListener(rootListener);
    }

    /**
     * Pick the right scope based on the current selection and push it to the
     * view. Package selection ⇒ recompute on the package's class subset.
     * Scope views otherwise use the displayed class subset; regular views
     * fall back to JAR-level metrics from the chart.
     */
    private void applyCurrentScope() {
        if (boundView == null) {
            return;
        }
        DomainModel model = boundView.getDomainModel();
        String selected = boundView.getSelectedFullName();

        if (model != null && selected != null && model.getClass(selected) == null) {
            // Selection is not a known class → treat as a package scope.
            QualityMetrics scoped = QualityMetrics.computeForPackage(model, selected);
            qualityView.setMetrics(scoped, selected);
        } else if (model != null && boundView.getScopeExtensionSourceRoot() != null) {
            Set<String> scopedClasses = collectClassNames(boundView.getArchitectureRoot());
            QualityMetrics scoped = QualityMetrics.computeForClasses(model, scopedClasses);
            qualityView.setMetrics(scoped, "Current view");
        } else {
            qualityView.setMetrics(boundView.getQualityMetrics(), null);
        }
    }

    private static Set<String> collectClassNames(ArchitectureNode root) {
        Set<String> classNames = new HashSet<>();
        collectClassNames(root, classNames);
        return classNames;
    }

    private static void collectClassNames(ArchitectureNode node, Set<String> classNames) {
        if (node == null) {
            return;
        }
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            classNames.add(node.getFullName());
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectClassNames(child, classNames);
        }
    }

    private void unbind() {
        if (boundView != null) {
            if (metricsListener != null) {
                boundView.qualityMetricsProperty().removeListener(metricsListener);
            }
            if (selectionListener != null) {
                boundView.selectedFullNameProperty().removeListener(selectionListener);
            }
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
        }
        boundView = null;
        metricsListener = null;
        selectionListener = null;
        rootListener = null;
    }

    private ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView focused = wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> v == wm.getFocusedView())
                .findFirst()
                .orElse(null);
        if (focused != null) {
            return focused;
        }

        ArchitectureWfxView current = wrapperFor(boundView);
        if (current != null) {
            return current;
        }

        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .findFirst()
                .orElse(null);
    }

    private ArchitectureWfxView wrapperFor(ArchitectureView view) {
        if (view == null) {
            return null;
        }
        return Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(wrapper -> wrapper.getArchitectureView() == view)
                .findFirst()
                .orElse(null);
    }
}
