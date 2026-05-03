package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;

/**
 * Carries a method selection request between UI components without coupling
 * graph overlays and side panels directly. A null class/method pair means
 * "clear the current method selection".
 */
public class MethodSelectionEvent extends EventObject {

    private final String className;
    private final String methodName;
    private final String descriptor;

    public MethodSelectionEvent(String className, String methodName, String descriptor, Object source) {
        super(source);
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getDescriptor() {
        return descriptor;
    }
}
