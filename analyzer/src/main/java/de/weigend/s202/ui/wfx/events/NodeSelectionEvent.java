package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;

/**
 * Carries a selection request between UI components without coupling them
 * directly. The full name may identify either a class or a package — the
 * receiver decides what to do based on its data (e.g. the outline tree
 * navigates to whichever node has that full name).
 *
 * <p>Publishers set themselves as the {@code source} so subscribers can skip
 * echoes of their own publishes.
 */
public class NodeSelectionEvent extends EventObject {

    private final String fullName;

    public NodeSelectionEvent(String fullName, Object source) {
        super(source);
        this.fullName = fullName;
    }

    public String getFullName() {
        return fullName;
    }
}
