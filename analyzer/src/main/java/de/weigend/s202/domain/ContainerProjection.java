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
package de.weigend.s202.domain;

import java.util.Arrays;

/**
 * Projects class dependencies onto containers — the one rule behind the
 * hierarchical levelization: a dependency {@code X → Y} belongs to the deepest
 * container holding both classes and becomes an edge between the two children
 * of that container whose subtrees hold X and Y.
 */
public final class ContainerProjection {

    private ContainerProjection() {
    }

    /** Deepest common container of a class dependency and the two siblings it connects. */
    public record Projection(String container, String fromSibling, String toSibling) {
        public String key() {
            return fromSibling + "\0" + toSibling;
        }
    }

    /**
     * @return the projection, or {@code null} when both fqns map onto the same
     *         sibling (only possible for a class and a package of the same name)
     */
    public static Projection project(String fromClass, String toClass) {
        String[] a = fromClass.split("\\.");
        String[] b = toClass.split("\\.");
        int i = 0;
        while (i < a.length - 1 && i < b.length - 1 && a[i].equals(b[i])) {
            i++;
        }
        String container = String.join(".", Arrays.copyOf(a, i));
        String fromSibling = i == a.length - 1 ? fromClass : join(container, a[i]);
        String toSibling = i == b.length - 1 ? toClass : join(container, b[i]);
        if (fromSibling.equals(toSibling)) {
            return null;
        }
        return new Projection(container, fromSibling, toSibling);
    }

    public static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String join(String container, String child) {
        return container.isEmpty() ? child : container + "." + child;
    }
}
