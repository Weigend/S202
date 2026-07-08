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
package de.weigend.s202.ui.core.events;

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
