package de.weigend.s202.ui.wfx.whatif;

import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.wfx.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.outline.OutlineExplorerView;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

/**
 * WFX module providing the What-If Dependencies side panel. Auto-discovered
 * by Avaje, registered LEFT of the central docking area (stacked under the
 * Outline Explorer when present). Loosely coupled to the chart: it observes
 * {@link WindowManager#focusedViewProperty()}, rebinds the view to whichever
 * architecture is focused, and listens to that view's
 * {@link ArchitectureView#redrawTickProperty()} so the panel refreshes
 * after every layout pulse — same trigger the orange-edge renderer uses.
 */
@Singleton
@Priority(22)
public class WhatIfDependenciesModule implements Module {

    private WhatIfDependenciesView view;

    private ArchitectureView boundView;
    private ChangeListener<Object> rootListener;
    private ChangeListener<Object> rawModelListener;
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
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying What-If Dependencies preload", e);
        }
    }

    @Override
    public void start() {
        WindowManager wm = Lookup.lookup(WindowManager.class);

        // Stack next to the outline (vertical split inside the LEFT region)
        // when it's available; fall back to a plain LEFT registration.
        View outline = wm.findView(OutlineExplorerView.VIEW_ID);
        if (outline != null) {
            wm.register(view, outline);
        } else {
            wm.register(view);
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
        redrawListener = (o, w, n) -> view.refresh();
        newBound.architectureRootProperty().addListener(rootListener);
        newBound.rawDependencyModelProperty().addListener(rawModelListener);
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
            if (redrawListener != null) {
                boundView.redrawTickProperty().removeListener(redrawListener);
            }
        }
        boundView = null;
        rootListener = null;
        rawModelListener = null;
        redrawListener = null;
    }

    private void pushCurrent() {
        if (boundView == null) {
            view.bind(null, null, null);
            return;
        }
        view.bind(
                boundView.getWhatIfModel(),
                boundView.getRawDependencyModel(),
                boundView.getWhatIfRenderer());
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
