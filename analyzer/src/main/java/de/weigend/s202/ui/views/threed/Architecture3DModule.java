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
package de.weigend.s202.ui.views.threed;

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.graph.GraphSelection;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.platform.AnalysisDocument;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ArchitectureWfxView;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Parent;

import java.util.EventObject;

/**
 * WFX module that registers and drives {@link ArchitectureView3D}.
 *
 * <p>Tracks the focused {@link ArchitectureWfxView}. Whenever its picture
 * changes, waits two JavaFX pulses, so that the 2D layout is settled, and
 * then reads the picture off the 2D view, {@link TopViewModel#read}. A view
 * no one looks at waits until it is shown.
 */
@Singleton
@Priority(35)
public class Architecture3DModule implements Module {

    private ArchitectureView3D view;
    private ArchitectureCanvas   boundView;
    private ChangeListener<ArchitectureNode> rootListener;
    private ChangeListener<Boolean> showDependenciesListener;
    private ChangeListener<Boolean> showSccListener;
    private ChangeListener<Boolean> showPackageSccListener;
    private ChangeListener<Boolean> showViolationsListener;
    private ChangeListener<Number> redrawTickListener;
    /** How often in a row the picture was read without boxes, and how often that is tried. */
    private int emptyReads;
    private static final int MAX_EMPTY_READS = 5;
    /** Whether the view missed a redraw while it was not shown. */
    private boolean stale;
    /** Whether a rebuild is already on its way. */
    private boolean refreshQueued;
    /** Whether the coming rebuild resets the camera. */
    private boolean resetPending;
    private ChangeListener<Parent> activationListener;
    private LookupContext.Subscription activeViewSubscription;

    @Override
    public String getName() { return "3D Architecture View"; }

    @Override
    public void preload() throws PlatformException {
        view = new ArchitectureView3D();
        view.setOnElementSelected(fqn -> {
            if (fqn == null) {
                // The arrows of the 2.5D view are those of 2D: they go when 2D no longer selects anything.
                GraphSelection.clear();
            } else {
                eventBus().publish(new NodeSelectionEvent(fqn, view));
            }
        });
        view.setOnPictureChanged(this::refreshFlat);
        // The mapping belongs to the analysis and is saved with it.
        view.setOnMappingChanged(mapping -> {
            AnalysisDocument document = boundView == null ? null
                    : Lookup.lookup(ArchitectureViewManager.class).documentOf(boundView);
            if (document != null) {
                document.setMetricMapping(mapping);
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        wm.register(view, false); // hidden at startup; visible via View menu on demand
        installActivationOnShow(wm);
        installCatchUp();
        LookupContext active = wm.getActiveLookup();
        activeViewSubscription = active.observe(ArchitectureCanvas.class,
                canvases -> rebindTo(canvases.isEmpty() ? null : canvases.get(0)));
        rebindTo(active.lookup(ArchitectureCanvas.class));

        EventBus<EventObject> bus = (EventBus<EventObject>) Lookup.lookup(EventBus.class);
        bus.subscribe(NodeSelectionEvent.class, ev -> {
            if (ev.getSource() != view) {
                Platform.runLater(() -> view.selectByFullName(ev.getFullName()));
            }
            return true;
        });
    }

    @Override
    public void stop() {
        if (activationListener != null) {
            view.getRootNode().parentProperty().removeListener(activationListener);
            activationListener = null;
        }
        if (activeViewSubscription != null) {
            activeViewSubscription.cancel();
            activeViewSubscription = null;
        }
        unbind();
    }

    // -----------------------------------------------------------------------

    private void rebindTo(ArchitectureCanvas newBound) {
        if (newBound == boundView) return;

        unbind();
        boundView = newBound;
        scheduleRefresh(true);

        if (newBound != null) {
            rootListener = (obs, was, isNow) -> scheduleRefresh(true);
            newBound.architectureRootProperty().addListener(rootListener);
            // Switching arrows off only hides them in 2D, without a redraw: the 2.5D view reads them anew.
            showDependenciesListener = (obs, was, isNow) -> refreshFlat();
            showSccListener = (obs, was, isNow) -> refreshFlat();
            showViolationsListener = (obs, was, isNow) -> refreshFlat();
            newBound.showDependenciesProperty().addListener(showDependenciesListener);
            newBound.showSccProperty().addListener(showSccListener);
            showPackageSccListener = (obs, was, isNow) -> refreshFlat();
            newBound.showPackageSccProperty().addListener(showPackageSccListener);
            newBound.showWhatIfViolationsProperty().addListener(showViolationsListener);
            redrawTickListener = (obs, was, isNow) -> scheduleRefresh(false);
            newBound.redrawTickProperty().addListener(redrawTickListener);
        }
    }

    private void refreshFlat() {
        scheduleRefresh(false);
    }


    /**
     * The View menu calls {@link WindowManager#showView(io.softwareecg.wfx.windowmanager.api.View)}.
     * Defer one pulse after the 3D root is attached, then call it again so the
     * corresponding tab is selected even if the first attach happened while the
     * tab pane was still being assembled.
     */
    private void installActivationOnShow(WindowManager wm) {
        activationListener = (obs, was, parent) -> {
            if (parent == null) {
                return;
            }
            Platform.runLater(() -> {
                if (wm.hasRegisteredView(view)) {
                    wm.showView(view);
                }
            });
        };
        view.getRootNode().parentProperty().addListener(activationListener);
    }

    /**
     * Waits two JavaFX layout pulses before reading the 2D bounds, so the
     * ArchitectureCanvas's scene graph is fully laid out.
     *
     * @param resetCamera true when the architecture root changed or the bound view switched;
     *                    false for pure redraw ticks (selection, violations toggle) where the
     *                    camera position should be preserved.
     */
    private void scheduleRefresh(boolean resetCamera) {
        resetPending |= resetCamera;
        if (boundView == null) {
            view.setPicture(null, true);
            return;
        }
        // The 2D picture redraws often (selection, hover, layout); a 3D view no
        // one looks at waits until it is shown, and several redraws make one rebuild.
        if (!isShowing()) {
            stale = true;
            return;
        }
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        // Capture reference; boundView may change before the lambdas execute
        ArchitectureCanvas captured = boundView;
        Platform.runLater(() -> Platform.runLater(() -> {
            refreshQueued = false;
            if (captured != boundView) return; // stale — a newer rebind won
            boolean reset = resetPending;
            resetPending = false;
            stale = false;
            AnalysisDocument document = Lookup.lookup(ArchitectureViewManager.class).documentOf(captured);
            view.setMetrics(captured.getRawDependencyModel(), captured.getArchitectureRoot(),
                    document == null ? null : document.metricMapping());
            TopViewModel picture = TopViewModel.read(captured);
            view.setPicture(picture, reset);
            // A tab just opened may not be laid out yet: read it again a little later.
            if (picture.roots().isEmpty() && captured.getArchitectureRoot() != null && emptyReads++ < MAX_EMPTY_READS) {
                Platform.runLater(() -> scheduleRefresh(false));
            } else if (!picture.roots().isEmpty()) {
                emptyReads = 0;
            }
        }));
    }

    /** Whether the 3D view is in front: in a scene, and neither it nor anything around it hidden. */
    private boolean isShowing() {
        javafx.scene.Node node = view.getRootNode();
        if (node.getScene() == null || node.getScene().getWindow() == null
                || !node.getScene().getWindow().isShowing()) {
            return false;
        }
        for (; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Catches up when the view comes to the front. Choosing a tab makes its
     * content visible, so the view watches the visibility of everything
     * around it, and the chain anew whenever it is put somewhere else.
     */
    private void installCatchUp() {
        javafx.beans.InvalidationListener check = obs -> {
            if (stale && !refreshQueued && isShowing()) {
                scheduleRefresh(false);
            }
        };
        java.util.List<javafx.beans.Observable> watched = new java.util.ArrayList<>();
        Runnable[] watch = new Runnable[1];
        javafx.beans.InvalidationListener moved = obs -> {
            watch[0].run();
            check.invalidated(obs);
        };
        watch[0] = () -> {
            watched.forEach(observable -> {
                observable.removeListener(check);
                observable.removeListener(moved);
            });
            watched.clear();
            for (javafx.scene.Node node = view.getRootNode(); node != null; node = node.getParent()) {
                node.visibleProperty().addListener(check);
                node.parentProperty().addListener(moved);
                watched.add(node.visibleProperty());
                watched.add(node.parentProperty());
            }
        };
        view.getRootNode().sceneProperty().addListener(moved);
        watch[0].run();
    }

    private void unbind() {
        if (boundView != null && rootListener != null) {
            boundView.architectureRootProperty().removeListener(rootListener);
        }
        if (boundView != null && showDependenciesListener != null) {
            boundView.showDependenciesProperty().removeListener(showDependenciesListener);
        }
        if (boundView != null && showSccListener != null) {
            boundView.showSccProperty().removeListener(showSccListener);
        }
        if (boundView != null && showPackageSccListener != null) {
            boundView.showPackageSccProperty().removeListener(showPackageSccListener);
        }
        if (boundView != null && showViolationsListener != null) {
            boundView.showWhatIfViolationsProperty().removeListener(showViolationsListener);
        }
        if (boundView != null && redrawTickListener != null) {
            boundView.redrawTickProperty().removeListener(redrawTickListener);
        }
        boundView    = null;
        rootListener = null;
        showDependenciesListener = null;
        showSccListener = null;
        showPackageSccListener = null;
        showViolationsListener = null;
        redrawTickListener = null;
    }

    @SuppressWarnings("unchecked")
    private static EventBus<EventObject> eventBus() {
        return (EventBus<EventObject>) Lookup.lookup(EventBus.class);
    }
}
