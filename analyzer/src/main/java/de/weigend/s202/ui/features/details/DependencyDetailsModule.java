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
package de.weigend.s202.ui.features.details;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.core.events.DependencySelectionEvent;
import de.weigend.s202.ui.core.events.MethodSelectionEvent;
import de.weigend.s202.ui.features.whatif.WhatIfDependenciesView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

import java.util.EventObject;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * WFX module providing the Dependency Details panel. Follows
 * {@link WindowManager#getActiveLookup()}: the panel explains the arrow last
 * selected in the active architecture tab, and keeps up with what that tab shows
 * of the plan.
 */
// Priority 29: after the Dependencies panel (28), beside which this one is docked.
@Singleton
@Priority(29)
public class DependencyDetailsModule implements Module {

    private DependencyDetailsView view;
    private LookupContext.Subscription activeViewSubscription;

    private ArchitectureCanvas boundView;
    private ChangeListener<Number> redrawListener;

    /** The selected arrow of every tab, so that coming back to a tab shows it again. */
    private final Map<ArchitectureCanvas, DependencySelection> selections = new WeakHashMap<>();

    // What the panel was last filled from: a redraw of the arrows calls for a
    // new content only when the model or the plan has changed.
    private DomainModel shownModel;
    private PlanState shownPlan = PlanState.NOTHING_PLANNED;
    private EdgeCycleClassifier cycles;

    @Override
    public String getName() {
        return "Dependency Details";
    }

    @Override
    public void preload() {
        view = new DependencyDetailsView();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        view.setOnReveal(place -> bus.publish(new MethodSelectionEvent(
                place.className(), place.method(), place.descriptor(), view)));

        WindowManager wm = Lookup.lookup(WindowManager.class);
        View dependencies = wm.findView(WhatIfDependenciesView.VIEW_ID);
        if (dependencies != null) {
            wm.register(view, dependencies);
        } else {
            wm.register(view);
        }

        bus.subscribe(DependencySelectionEvent.class, ev -> {
            if (ev.getSource() instanceof ArchitectureCanvas canvas) {
                selections.put(canvas, ev.getSelection());
                if (canvas == boundView) {
                    showSelection();
                }
            }
            return true;
        });

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
        if (newBound == boundView) {
            return;
        }
        unbind();
        boundView = newBound;
        if (newBound != null) {
            redrawListener = (obs, was, isNow) -> refreshIfOutdated();
            newBound.redrawTickProperty().addListener(redrawListener);
        }
        showSelection();
    }

    private void unbind() {
        if (boundView != null && redrawListener != null) {
            boundView.redrawTickProperty().removeListener(redrawListener);
        }
        boundView = null;
        redrawListener = null;
    }

    private void refreshIfOutdated() {
        if (boundView.getDomainModel() != shownModel || !planOf(boundView).equals(shownPlan)) {
            showSelection();
        }
    }

    private void showSelection() {
        DependencySelection selection = boundView == null ? null : selections.get(boundView);
        DomainModel model = boundView == null ? null : boundView.getDomainModel();
        PlanState plan = planOf(boundView);
        if (model != shownModel || !plan.equals(shownPlan)) {
            shownModel = model;
            shownPlan = plan;
            cycles = null;
        }
        if (model == null || selection == null || selection.isEmpty() || boundView.getRawDependencyModel() == null) {
            view.show(null);
            return;
        }
        if (cycles == null) {
            cycles = EdgeCycleClassifier.of(model, plan.cuts(), Lookup.lookup(SCCFinder.class), plan.placement());
        }
        view.show(new DetailContentBuilder(boundView.getRawDependencyModel(), cycles, plan.cuts()).build(selection));
    }

    private static PlanState planOf(ArchitectureCanvas canvas) {
        return canvas == null ? PlanState.NOTHING_PLANNED : canvas.getPlan();
    }
}
