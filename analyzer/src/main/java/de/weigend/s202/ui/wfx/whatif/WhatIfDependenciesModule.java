package de.weigend.s202.ui.wfx.whatif;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.wfx.ArchitectureWfxView;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

/**
 * WFX module providing the What-If Dependencies panel. Auto-discovered by
 * Avaje, registered in the BOTTOM dock area under the architecture view.
 * Loosely coupled to the chart: observes
 * {@link WindowManager#focusedViewProperty()}, rebinds the view to
 * whichever architecture is focused, and listens to that view's
 * {@link ArchitectureView#redrawTickProperty()} so the panel refreshes
 * after every layout pulse — same trigger the orange-edge renderer uses.
 */
@Singleton
@Priority(28)
public class WhatIfDependenciesModule implements Module {

    private WhatIfDependenciesView view;

    private ArchitectureView boundView;
    private ChangeListener<Object> rootListener;
    private ChangeListener<Object> rawModelListener;
    private ChangeListener<Object> architectureListener;
    private ChangeListener<Object> whatIfArchitectureListener;
    private ChangeListener<Number> redrawListener;

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
    public void start() {
        // Don't auto-register. The view is docked manually by S202Module
        // after the first architecture view is opened, via dockUnder(...)
        // — that's the only point where we know which CENTER editor area
        // to split. Focus tracking still drives the bind/refresh wiring.
        WindowManager wm = Lookup.lookup(WindowManager.class);
        wm.focusedViewProperty().addListener((obs, was, isNow) -> rebindToFocusedView());
        rebindToFocusedView();
    }

    /**
     * Dock the Dependencies panel directly under the given architecture
     * view, splitting the CENTER editor area vertically. No-op when the
     * panel is already registered with the WindowManager — subsequent
     * architecture views share the same panel via focus tracking. After
     * "Close Project" tears the panel out of the WindowManager, the next
     * dockUnder re-registers it.
     */
    public void dockUnder(ArchitectureWfxView anchor) {
        if (anchor == null) {
            return;
        }
        WindowManager wm = Lookup.lookup(WindowManager.class);
        if (wm.hasRegisteredView(view)) {
            return;
        }
        wm.register(view, anchor);
    }

    @Override
    public void stop() {
        unbind();
    }

    private void rebindToFocusedView() {
        ArchitectureWfxView focused = focusedArchitectureView();
        ArchitectureView newBound = focused == null ? null : focused.getArchitectureView();
        if (newBound == boundView) {
            return;
        }

        unbind();

        if (newBound == null) {
            view.bind(null, null);
            return;
        }

        boundView = newBound;
        pushCurrent();

        rootListener = (o, w, n) -> pushCurrent();
        rawModelListener = (o, w, n) -> pushCurrent();
        architectureListener = (o, w, n) -> pushCurrent();
        whatIfArchitectureListener = (o, w, n) -> pushCurrent();
        redrawListener = (o, w, n) -> view.refresh();
        newBound.architectureRootProperty().addListener(rootListener);
        newBound.rawDependencyModelProperty().addListener(rawModelListener);
        newBound.architectureProperty().addListener(architectureListener);
        newBound.whatIfArchitectureProperty().addListener(whatIfArchitectureListener);
        newBound.redrawTickProperty().addListener(redrawListener);
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
        }
        boundView = null;
        rootListener = null;
        rawModelListener = null;
        architectureListener = null;
        whatIfArchitectureListener = null;
        redrawListener = null;
    }

    private void pushCurrent() {
        if (boundView == null) {
            view.bind(null, null);
            return;
        }
        Architecture wif = boundView.getWhatIfArchitecture();
        Architecture displayed = wif != null ? wif : boundView.getArchitecture();
        view.bind(displayed, boundView.getRawDependencyModel());
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
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .findFirst()
                .orElse(null);
    }
}
