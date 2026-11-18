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
package de.weigend.s202.ui.features.outline;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.events.MethodSelectionEvent;
import de.weigend.s202.ui.core.events.NodeSelectionEvent;
import de.weigend.s202.ui.core.events.OpenScopeEvent;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

import java.util.EventObject;

/**
 * WFX module providing the Outline Explorer side panel. Auto-discovered by
 * Avaje as a {@link Module} bean, registered LEFT of the central docking area.
 * <p>
 * Follows {@link WindowManager#getActiveLookup()}: the outline mirrors the
 * architecture root of the tab the user works in and stays in sync via that
 * tab's {@link ArchitectureCanvas#architectureRootProperty()}. It keeps
 * showing that tab while the focus is in a panel; when all tabs close, the
 * outline clears.
 * <p>
 * Node selection (class or package) is exchanged with the chart purely through
 * {@link NodeSelectionEvent} on the bus — the outline neither calls into the
 * chart nor vice versa.
 */
@Singleton
@Priority(10)
public class OutlineExplorerModule implements Module {

    private OutlineExplorerView outlineView;

    /** Currently mirrored architecture view, or null. */
    private ArchitectureCanvas boundView;
    /** Listener attached to {@link #boundView}'s root property. */
    private ChangeListener<ArchitectureNode> rootListener;
    /** Listener attached to {@link #boundView}'s raw dependency model property. */
    private ChangeListener<DependencyModel> rawModelListener;
    private LookupContext.Subscription activeViewSubscription;

    @Override
    public String getName() {
        return "Outline Explorer";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void preload() throws PlatformException {
        waitForDemoPreloader();
        outlineView = new OutlineExplorerView();

        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        outlineView.setOnNodeClick(fqn ->
                bus.publish(new NodeSelectionEvent(fqn, outlineView)));
        outlineView.setOnOpenScope(scope ->
                bus.publish(new OpenScopeEvent(scope, boundView, outlineView)));
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying Outline Explorer preload", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        wm.register(outlineView);

        LookupContext active = wm.getActiveLookup();
        activeViewSubscription = active.observe(ArchitectureCanvas.class,
                canvases -> rebindTo(canvases.isEmpty() ? null : canvases.get(0)));
        rebindTo(active.lookup(ArchitectureCanvas.class));

        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        bus.subscribe(NodeSelectionEvent.class, ev -> {
            // Skip our own publishes — the user already clicked the tree row.
            if (ev.getSource() != outlineView) {
                outlineView.revealByFullName(ev.getFullName());
            }
            return true;
        });
        bus.subscribe(MethodSelectionEvent.class, ev -> {
            if (ev.getSource() != outlineView) {
                if (ev.getClassName() == null && ev.getMethodName() == null) {
                    outlineView.clearSelection();
                } else {
                    outlineView.revealMethod(ev.getClassName(), ev.getMethodName(), ev.getDescriptor());
                }
            }
            return true;
        });
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
        // Idempotent: if we're still bound to the same chart there's nothing
        // to do. Rebuilding the TreeView would silently drop the user's
        // expansion and selection state.
        if (newBound == boundView) {
            return;
        }

        unbind();

        if (newBound == null) {
            outlineView.setArchitectureRoot(null);
            return;
        }

        boundView = newBound;
        updateOutline();

        rootListener = (obs, was, isNow) -> updateOutline();
        rawModelListener = (obs, was, isNow) -> updateOutline();
        newBound.architectureRootProperty().addListener(rootListener);
        newBound.rawDependencyModelProperty().addListener(rawModelListener);
    }

    private void unbind() {
        if (boundView != null) {
            if (rootListener != null) {
                boundView.architectureRootProperty().removeListener(rootListener);
            }
            if (rawModelListener != null) {
                boundView.rawDependencyModelProperty().removeListener(rawModelListener);
            }
        }
        boundView = null;
        rootListener = null;
        rawModelListener = null;
    }

    private void updateOutline() {
        if (boundView == null) {
            outlineView.setArchitectureRoot(null);
            return;
        }
        outlineView.setArchitectureRoot(boundView.getArchitectureRoot(), boundView.getRawDependencyModel());
    }
}
