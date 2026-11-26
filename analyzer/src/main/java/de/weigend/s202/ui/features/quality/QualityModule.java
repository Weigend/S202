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
package de.weigend.s202.ui.features.quality;

import de.weigend.s202.analysis.quality.QualityCalculator;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.features.outline.OutlineExplorerView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
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
 * Follows {@link WindowManager#getActiveLookup()}: the plot shows the
 * architecture tab the user works in, also while the focus is in a panel - the
 * selected package, otherwise the classes the tab shows. Every part is measured
 * the same way, see {@link QualityCalculator}, and as the tab shows it: with
 * the plan or without.
 */
@Singleton
@Priority(20)
public class QualityModule implements Module {

    private QualityView qualityView;

    private ArchitectureCanvas boundView;
    private ChangeListener<DomainModel> modelListener;
    private ChangeListener<PlanState> planListener;
    private ChangeListener<String> selectionListener;
    private ChangeListener<ArchitectureNode> rootListener;
    private LookupContext.Subscription activeViewSubscription;

    // What was measured last; measuring a large model takes a moment.
    private QualityCalculator calculator;
    private DomainModel measuredModel;
    private Placement measuredPlacement;

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

        LookupContext active = wm.getActiveLookup();
        activeViewSubscription = active.observe(ArchitectureCanvas.class,
                canvases -> rebindTo(canvases.isEmpty() ? null : canvases.get(0)));
        rebindTo(active.lookup(ArchitectureCanvas.class));
    }

    @Override
    public void stop() {
        if (activeViewSubscription != null) {
            activeViewSubscription.cancel();
            activeViewSubscription = null;
        }
        unbind();
    }

    private void rebindTo(ArchitectureCanvas newBound) {
        // Same as OutlineExplorerModule: nothing to do for the chart we are
        // already bound to.
        if (newBound == boundView) {
            return;
        }

        unbind();

        if (newBound == null) {
            calculator = null;
            measuredModel = null;
            qualityView.setMetrics(null, null);
            return;
        }

        boundView = newBound;
        applyCurrentScope();

        modelListener = (obs, was, isNow) -> applyCurrentScope();
        planListener = (obs, was, isNow) -> applyCurrentScope();
        selectionListener = (obs, was, isNow) -> applyCurrentScope();
        rootListener = (obs, was, isNow) -> applyCurrentScope();
        newBound.domainModelProperty().addListener(modelListener);
        newBound.planProperty().addListener(planListener);
        newBound.selectedFullNameProperty().addListener(selectionListener);
        newBound.architectureRootProperty().addListener(rootListener);
    }

    private void applyCurrentScope() {
        if (boundView == null) {
            return;
        }
        DomainModel model = boundView.getShownModel();
        if (model == null) {
            qualityView.setMetrics(null, null);
            return;
        }
        QualityCalculator measured = calculatorFor(model, boundView.getPlan().placement());
        String selected = boundView.getSelectedFullName();
        Set<String> shown = collectClassNames(boundView.getArchitectureRoot());
        if (selected != null && model.getClass(selected) == null) {
            // Not a class, so a package.
            qualityView.setMetrics(measured.ofPackage(selected), selected);
        } else if (!shown.isEmpty() && shown.size() < model.getAllClasses().size()) {
            qualityView.setMetrics(measured.ofClasses(shown), "Current view");
        } else {
            qualityView.setMetrics(measured.ofAll(), null);
        }
    }

    private QualityCalculator calculatorFor(DomainModel model, Placement placement) {
        if (calculator == null || model != measuredModel || !placement.equals(measuredPlacement)) {
            calculator = new QualityCalculator(model, Lookup.lookup(SCCFinder.class), placement);
            measuredModel = model;
            measuredPlacement = placement;
        }
        return calculator;
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
            if (modelListener != null) {
                boundView.domainModelProperty().removeListener(modelListener);
            }
            if (planListener != null) {
                boundView.planProperty().removeListener(planListener);
            }
            if (selectionListener != null) {
                boundView.selectedFullNameProperty().removeListener(selectionListener);
            }
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
        }
        boundView = null;
        modelListener = null;
        planListener = null;
        selectionListener = null;
        rootListener = null;
    }
}
