package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;

/**
 * Published when the user removes a refactoring-preview cut edge and restores
 * it to the SCC graph.
 */
public class RestoreTangleEdgeEvent extends EventObject {

    private final String from;
    private final String to;

    public RestoreTangleEdgeEvent(String from, String to, Object source) {
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
