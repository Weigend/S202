package de.weigend.s202.ui.wfx.events;

import de.weigend.s202.ui.ArchitectureView;

import java.util.EventObject;

/**
 * Published when the user requests a dedicated architecture view scoped to a
 * package.
 */
public class OpenScopeEvent extends EventObject {

    private final String scope;
    private final ArchitectureView architectureView;

    public OpenScopeEvent(String scope, ArchitectureView architectureView, Object source) {
        super(source);
        this.scope = scope;
        this.architectureView = architectureView;
    }

    public String getScope() {
        return scope;
    }

    public ArchitectureView getArchitectureView() {
        return architectureView;
    }
}
