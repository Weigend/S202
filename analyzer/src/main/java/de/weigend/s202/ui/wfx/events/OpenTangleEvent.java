package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;
import java.util.Set;

/**
 * Bus event published by the Top Tangles side panel when the user requests
 * a dedicated view focused on a single tangle. Carries the tangle's class
 * full-names plus a stable key/title so the host shell can keep one view
 * per tangle entry.
 */
public class OpenTangleEvent extends EventObject {

    private final Set<String> members;
    private final String tangleKey;
    private final String title;

    public OpenTangleEvent(Set<String> members, String tangleKey, String title, Object source) {
        super(source);
        this.members = Set.copyOf(members);
        this.tangleKey = tangleKey;
        this.title = title;
    }

    /** Class full-names that form the tangle. */
    public Set<String> getMembers() {
        return members;
    }

    /** Stable identity for this tangle entry. */
    public String getTangleKey() {
        return tangleKey;
    }

    /** User-facing title for the dedicated tangle view. */
    public String getTitle() {
        return title;
    }
}
