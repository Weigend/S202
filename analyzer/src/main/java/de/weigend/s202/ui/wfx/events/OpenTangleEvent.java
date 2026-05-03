package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;
import java.util.Set;

/**
 * Bus event published by the Top Tangles side panel when the user requests
 * a dedicated view focused on a single tangle. Carries the tangle's class
 * full-names plus the specific from→to edge that triggered the request, so
 * the host shell can both filter the architecture and pre-highlight that
 * SCC edge in the new tab.
 */
public class OpenTangleEvent extends EventObject {

    private final Set<String> members;
    private final String fromClass;
    private final String toClass;

    public OpenTangleEvent(Set<String> members, String fromClass, String toClass, Object source) {
        super(source);
        this.members = Set.copyOf(members);
        this.fromClass = fromClass;
        this.toClass = toClass;
    }

    /** Class full-names that form the tangle. */
    public Set<String> getMembers() {
        return members;
    }

    /** Source class of the SCC edge to pre-select, or {@code null} if none. */
    public String getFromClass() {
        return fromClass;
    }

    /** Target class of the SCC edge to pre-select, or {@code null} if none. */
    public String getToClass() {
        return toClass;
    }
}
