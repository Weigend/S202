package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;

/**
 * Bus event fired when the user picks a SCC edge inside the Tangle tab's
 * graph overlay. The Top Tangles side panel listens and selects the
 * matching {@code from → to} row in its tree, so the textual
 * representation always mirrors the graph selection.
 */
public class TangleEdgeSelectedEvent extends EventObject {

    private final String from;
    private final String to;

    public TangleEdgeSelectedEvent(String from, String to, Object source) {
        super(source);
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
