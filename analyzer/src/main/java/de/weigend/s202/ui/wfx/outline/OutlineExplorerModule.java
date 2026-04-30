package de.weigend.s202.ui.wfx.outline;

import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.wfx.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.events.NodeSelectionEvent;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

import java.util.EventObject;

/**
 * WFX module providing the Outline Explorer side panel. Auto-discovered by
 * Avaje as a {@link Module} bean, registered LEFT of the central docking area.
 * <p>
 * Tracks {@link WindowManager#focusedViewProperty()}: when an
 * {@link ArchitectureWfxView} gains focus, the outline mirrors its
 * architecture root and stays in sync via the focused view's
 * {@link ArchitectureView#architectureRootProperty()}. When focus moves
 * elsewhere or all views close, the outline clears.
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
    private ArchitectureView boundView;
    /** Listener attached to {@link #boundView}'s root property. */
    private ChangeListener<ArchitectureNode> rootListener;

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
        outlineView.setOnNodeDoubleClick(fqn ->
                bus.publish(new NodeSelectionEvent(fqn, outlineView)));
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(2_000);
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

        wm.focusedViewProperty().addListener((obs, was, isNow) -> rebindToFocusedView());
        rebindToFocusedView();

        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        bus.subscribe(NodeSelectionEvent.class, ev -> {
            // Skip our own publishes — the user already clicked the tree row.
            if (ev.getSource() != outlineView) {
                outlineView.revealByFullName(ev.getFullName());
            }
            return true;
        });
    }

    @Override
    public void stop() {
        unbind();
    }

    private void rebindToFocusedView() {
        ArchitectureWfxView focused = focusedArchitectureView();
        ArchitectureView newBound = focused == null ? null : focused.getArchitectureView();

        // Idempotent: every focus change (including focus moving onto the
        // outline panel itself or onto the quality view) lands here, but if
        // we're still bound to the same chart there's nothing to do.
        // Rebuilding the TreeView would silently drop the user's expansion
        // and selection state.
        if (newBound == boundView) {
            return;
        }

        unbind();

        if (newBound == null) {
            outlineView.setArchitectureRoot(null);
            return;
        }

        boundView = newBound;
        outlineView.setArchitectureRoot(newBound.getArchitectureRoot());

        rootListener = (obs, was, isNow) -> outlineView.setArchitectureRoot(isNow);
        newBound.architectureRootProperty().addListener(rootListener);
    }

    private void unbind() {
        if (boundView != null && rootListener != null) {
            boundView.architectureRootProperty().removeListener(rootListener);
        }
        boundView = null;
        rootListener = null;
    }

    private ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> v == wm.getFocusedView())
                .findFirst()
                .orElseGet(() -> wm.getRegisteredViews().stream()
                        .filter(ArchitectureWfxView.class::isInstance)
                        .map(ArchitectureWfxView.class::cast)
                        .findFirst()
                        .orElse(null));
    }
}
