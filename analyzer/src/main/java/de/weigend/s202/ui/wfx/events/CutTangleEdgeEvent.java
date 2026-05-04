package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;

/**
 * Published when the user applies a recommended tangle cut edge from the
 * dedicated tangle view. This is an interactive UI cut: the persisted analysis
 * model is not mutated.
 */
public class CutTangleEdgeEvent extends EventObject {

    private final String from;
    private final String to;

    public CutTangleEdgeEvent(String from, String to, Object source) {
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
