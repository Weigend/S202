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
package de.weigend.s202.ui.wfx.view3d;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.wfx.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.ApplicationWindow;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.stage.Stage;

import java.util.EventObject;
import java.util.Map;

/**
 * WFX module that registers and drives {@link ArchitectureView3D}.
 *
 * <p>Tracks the focused {@link ArchitectureWfxView}. When the architecture
 * root changes, waits two JavaFX pulses (so the 2D layout is settled) and
 * then reads element footprints directly from the 2D view via
 * {@link ArchitectureView#getElementFootprintBoundsInScene()}.
 */
@Singleton
@Priority(35)
public class Architecture3DModule implements Module {

    private ArchitectureView3D view;
    private ArchitectureView   boundView;
    private ChangeListener<ArchitectureNode> rootListener;
    private ChangeListener<Boolean> showDependenciesListener;
    private ChangeListener<Boolean> showSccListener;
    private ChangeListener<Boolean> showViolationsListener;
    private ChangeListener<Number> redrawTickListener;
    private ChangeListener<Parent> activationListener;
    private Stage stage;

    @Override
    public String getName() { return "3D Architecture View"; }

    @Override
    public void preload() throws PlatformException {
        view = new ArchitectureView3D();
        view.setOnElementSelected(fqn -> eventBus().publish(new NodeSelectionEvent(fqn, view)));
    }

    @Override
    public void start() {
        stage = Lookup.lookup(ApplicationWindow.class).getStage();
        WindowManager wm = Lookup.lookup(WindowManager.class);
        wm.register(view, false); // hidden at startup; visible via View menu on demand
        installActivationOnShow(wm);
        wm.focusedViewProperty().addListener((obs, was, isNow) -> rebindToFocusedView());
        rebindToFocusedView();
    }

    @Override
    public void stop() {
        if (activationListener != null) {
            view.getRootNode().parentProperty().removeListener(activationListener);
            activationListener = null;
        }
        unbind();
    }

    // -----------------------------------------------------------------------

    private void rebindToFocusedView() {
        ArchitectureWfxView focused = focusedArchitectureView();
        ArchitectureView newBound = focused == null ? null : focused.getArchitectureView();
        if (newBound == boundView) return;

        unbind();
        boundView = newBound;
        syncOverlaySettings();
        scheduleRefresh();

        if (newBound != null) {
            rootListener = (obs, was, isNow) -> scheduleRefresh();
            newBound.architectureRootProperty().addListener(rootListener);
            showDependenciesListener = (obs, was, isNow) -> view.setShowDependencies(isNow);
            showSccListener = (obs, was, isNow) -> view.setShowScc(isNow);
            showViolationsListener = (obs, was, isNow) -> view.setShowViolations(isNow);
            newBound.showDependenciesProperty().addListener(showDependenciesListener);
            newBound.showSccProperty().addListener(showSccListener);
            newBound.showWhatIfViolationsProperty().addListener(showViolationsListener);
            redrawTickListener = (obs, was, isNow) -> scheduleRefresh();
            newBound.redrawTickProperty().addListener(redrawTickListener);
        }
    }

    private void syncOverlaySettings() {
        if (boundView == null) {
            view.setOverlayVisibility(false, false, false);
            return;
        }
        view.setOverlayVisibility(
                boundView.isShowDependencies(),
                boundView.isShowScc(),
                boundView.isShowWhatIfViolations());
    }

    /**
     * The View menu calls {@link WindowManager#showView(io.softwareecg.wfx.windowmtg.api.View)}.
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
     * ArchitectureView's scene graph is fully laid out.
     */
    private void scheduleRefresh() {
        if (boundView == null) {
            view.setData(null, null, null, stage);
            return;
        }
        // Capture reference; boundView may change before the lambdas execute
        ArchitectureView captured = boundView;
        Platform.runLater(() -> Platform.runLater(() -> {
            if (captured != boundView) return; // stale — a newer rebind won
            Map<String, Bounds> bounds = captured.getElementFootprintBoundsInScene();
            ArchitectureNode root = captured.getArchitectureRoot();
            Architecture architecture = captured.getWhatIfArchitecture() != null
                    ? captured.getWhatIfArchitecture()
                    : captured.getArchitecture();
            Map<String, String> visibleParentByFqn = captured.getVisibleElementParentFqns();
            view.setData(bounds, root, architecture, visibleParentByFqn, stage);
        }));
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
        showViolationsListener = null;
        redrawTickListener = null;
    }

    private ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView focused = wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> v == wm.getFocusedView())
                .findFirst()
                .orElse(null);
        if (focused != null) return focused;

        if (boundView != null) {
            ArchitectureWfxView current = wm.getRegisteredViews().stream()
                    .filter(ArchitectureWfxView.class::isInstance)
                    .map(ArchitectureWfxView.class::cast)
                    .filter(w -> w.getArchitectureView() == boundView)
                    .findFirst().orElse(null);
            if (current != null) return current;
        }
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static EventBus<EventObject> eventBus() {
        return (EventBus<EventObject>) Lookup.lookup(EventBus.class);
    }
}
