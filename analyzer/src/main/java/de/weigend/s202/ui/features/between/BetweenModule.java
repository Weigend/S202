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
package de.weigend.s202.ui.features.between;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Externals;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.events.DependencySelectionEvent;
import de.weigend.s202.ui.core.events.FromToEvent;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.PlanState;
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
import java.util.List;

/**
 * WFX module providing the From / To panel. Follows
 * {@link WindowManager#getActiveLookup()}: it asks among the classes of the
 * active architecture tab, as that shows them - with the plan or without. An
 * answer is a selected dependency like an arrow, so the Dependency Details
 * explain it; and an arrow or a matrix cell the user selects shows up here.
 */
// Priority 30: after the Dependencies panel (28), with which this one shares its place.
@Singleton
@Priority(30)
public class BetweenModule implements Module {

    private BetweenView view;
    private LookupContext.Subscription activeViewSubscription;

    private ArchitectureCanvas boundView;
    private ChangeListener<ArchitectureNode> rootListener;
    private ChangeListener<DomainModel> modelListener;
    private ChangeListener<PlanState> planListener;
    /** Keeps the panel from following its own answer. */
    private boolean answering;

    @Override
    public String getName() {
        return "From / To";
    }

    @Override
    public void preload() {
        view = new BetweenView();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        view.setOnQuestion((from, to, edges) -> answer(bus, from, to, edges));

        WindowManager wm = Lookup.lookup(WindowManager.class);
        View dependencies = wm.findView(WhatIfDependenciesView.VIEW_ID);
        if (dependencies != null) {
            wm.register(view, dependencies);
        } else {
            wm.register(view);
        }

        bus.subscribe(DependencySelectionEvent.class, ev -> {
            if (!answering && ev.getSource() == boundView && !ev.getSelection().isEmpty()) {
                view.follow(ev.getSelection().from(), ev.getSelection().to());
            }
            return true;
        });
        bus.subscribe(FromToEvent.class, ev -> {
            if (ev.getSource() == boundView) {
                wm.showView(view);
                view.ask(ev.getFrom(), ev.getTo());
            }
            return true;
        });

        LookupContext active = wm.getActiveLookup();
        activeViewSubscription = active.observe(ArchitectureCanvas.class,
                canvases -> rebindTo(canvases.isEmpty() ? null : canvases.get(0)));
        rebindTo(active.lookup(ArchitectureCanvas.class));
    }

    private void answer(EventBus<EventObject> bus, String from, String to, List<DependencyEdge> edges) {
        if (boundView == null) {
            return;
        }
        answering = true;
        try {
            bus.publish(new DependencySelectionEvent(edges.isEmpty() ? DependencySelection.NONE
                    : new DependencySelection(from == null ? BetweenView.name(null) : from,
                            to == null ? BetweenView.name(null) : to, edges), boundView));
        } finally {
            answering = false;
        }
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
            rootListener = (obs, was, isNow) -> showElements();
            modelListener = (obs, was, isNow) -> showElements();
            planListener = (obs, was, isNow) -> showElements();
            newBound.architectureRootProperty().addListener(rootListener);
            newBound.domainModelProperty().addListener(modelListener);
            newBound.planProperty().addListener(planListener);
        }
        showElements();
    }

    private void unbind() {
        if (boundView != null) {
            boundView.architectureRootProperty().removeListener(rootListener);
            boundView.domainModelProperty().removeListener(modelListener);
            boundView.planProperty().removeListener(planListener);
        }
        boundView = null;
        rootListener = null;
        modelListener = null;
        planListener = null;
    }

    private void showElements() {
        ArchitectureNode root = boundView == null ? null : boundView.getArchitectureRoot();
        DomainModel model = boundView == null ? null : boundView.getShownModel();
        if (root == null || model == null) {
            view.setElements(null, Between.NOTHING);
        } else {
            view.setElements(root, Between.of(root, model, Externals.of(boundView.getRawDependencyModel())));
        }
    }
}
