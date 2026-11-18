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
package de.weigend.s202.ui.features.whatif;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

/**
 * WFX module providing the What-If Dependencies panel. Auto-discovered by
 * Avaje, registered under the editor area that holds the architecture tabs.
 * Loosely coupled to the chart: observes
 * {@link WindowManager#getActiveLookup()}, rebinds the view to
 * whichever architecture tab is the active one, and listens to that view's
 * {@link ArchitectureCanvas#redrawTickProperty()} so the panel refreshes
 * after every layout pulse — same trigger the orange-edge renderer uses.
 */
@Singleton
@Priority(28)
public class WhatIfDependenciesModule implements Module {

    private WhatIfDependenciesView view;

    private ArchitectureCanvas boundView;
    private ChangeListener<Object> rootListener;
    private ChangeListener<Object> rawModelListener;
    private ChangeListener<Object> architectureListener;
    private ChangeListener<Object> whatIfArchitectureListener;
    private ChangeListener<Number> redrawListener;
    private ChangeListener<String> selectionListener;
    private LookupContext.Subscription activeViewSubscription;

    @Override
    public String getName() {
        return "What-If Dependencies";
    }

    @Override
    public void preload() throws PlatformException {
        waitForDemoPreloader();
        view = new WhatIfDependenciesView();
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying What-If Dependencies preload", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        io.softwareecg.wfx.platform.api.EventBus<java.util.EventObject> bus =
                Lookup.lookup(io.softwareecg.wfx.platform.api.EventBus.class);
        view.setOnOpenTangle(req -> bus.publish(new de.weigend.s202.ui.core.events.OpenTangleEvent(
                new java.util.HashSet<>(req.tangle().members()),
                String.join("|", req.tangle().members()),
                req.title(),
                view)));
        // The view anchors itself to the editor area, so it is registered at
        // startup like the other panels; the architecture tabs open above it.
        WindowManager wm = Lookup.lookup(WindowManager.class);
        wm.register(view);
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

        if (newBound == null) {
            view.bind(null, null, null);
            return;
        }

        boundView = newBound;
        pushCurrent();

        rootListener = (o, w, n) -> pushCurrent();
        rawModelListener = (o, w, n) -> pushCurrent();
        architectureListener = (o, w, n) -> pushCurrent();
        whatIfArchitectureListener = (o, w, n) -> pushCurrent();
        redrawListener = (o, w, n) -> view.refresh();
        selectionListener = (o, w, n) -> pushCurrent();
        newBound.architectureRootProperty().addListener(rootListener);
        newBound.rawDependencyModelProperty().addListener(rawModelListener);
        newBound.architectureProperty().addListener(architectureListener);
        newBound.whatIfArchitectureProperty().addListener(whatIfArchitectureListener);
        newBound.redrawTickProperty().addListener(redrawListener);
        newBound.selectedFullNameProperty().addListener(selectionListener);
    }

    private void unbind() {
        if (boundView != null) {
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
            if (rawModelListener != null) {
                boundView.rawDependencyModelProperty().removeListener(rawModelListener);
            }
            if (architectureListener != null) {
                boundView.architectureProperty().removeListener(architectureListener);
            }
            if (whatIfArchitectureListener != null) {
                boundView.whatIfArchitectureProperty().removeListener(whatIfArchitectureListener);
            }
            if (redrawListener != null) {
                boundView.redrawTickProperty().removeListener(redrawListener);
            }
            if (selectionListener != null) {
                boundView.selectedFullNameProperty().removeListener(selectionListener);
            }
        }
        boundView = null;
        rootListener = null;
        rawModelListener = null;
        architectureListener = null;
        whatIfArchitectureListener = null;
        redrawListener = null;
        selectionListener = null;
    }

    private void pushCurrent() {
        if (boundView == null) {
            view.bind(null, null, null);
            return;
        }
        Architecture wif = boundView.getWhatIfArchitecture();
        Architecture displayed = wif != null ? wif : boundView.getArchitecture();
        view.bind(displayed, boundView.getRawDependencyModel(), boundView.getSelectedFullName(),
                visibleClassesOf(boundView));
    }

    /**
     * A tangle tab carries the full model of its source view but displays only
     * the classes of its tangle. Its panel content is limited to those classes;
     * regular architecture and scope views are not restricted.
     */
    private static java.util.Set<String> visibleClassesOf(ArchitectureCanvas canvas) {
        if (canvas.isScopeOwner() || canvas.getArchitectureRoot() == null) {
            return null;
        }
        java.util.Set<String> classes = new java.util.HashSet<>();
        collectClasses(canvas.getArchitectureRoot(), classes);
        return classes;
    }

    private static void collectClasses(de.weigend.s202.ui.core.model.ArchitectureNode node,
                                       java.util.Set<String> out) {
        if (node.getType() == de.weigend.s202.ui.core.model.ArchitectureNode.NodeType.CLASS) {
            out.add(node.getFullName());
        }
        for (de.weigend.s202.ui.core.model.ArchitectureNode child : node.getChildren()) {
            collectClasses(child, out);
        }
    }
}
