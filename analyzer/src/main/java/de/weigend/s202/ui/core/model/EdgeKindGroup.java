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
package de.weigend.s202.ui.core.model;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** The kinds of dependency a view can hide, as the user sees them. */
public enum EdgeKindGroup {

    CALLS("Calls", EdgeKind.CALLS, EdgeKind.INSTANTIATES),
    INHERITANCE("Inheritance", EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.NESTED_INHERITANCE),
    IMPORTS("Imports", EdgeKind.IMPORTS),
    REFERENCES("References", EdgeKind.USES);

    private final String label;
    private final Set<EdgeKind> kinds;

    EdgeKindGroup(String label, EdgeKind first, EdgeKind... rest) {
        this.label = label;
        this.kinds = EnumSet.of(first, rest);
    }

    public String label() {
        return label;
    }

    /**
     * Whether a view that hides {@code hidden} still shows {@code from → to}:
     * a dependency disappears only when everything it consists of is hidden.
     * One whose kinds are unknown is always shown.
     */
    public static boolean shows(Collection<EdgeKindGroup> hidden, DependencyModel model, String from, String to) {
        if (hidden.isEmpty()) {
            return true;
        }
        DependencyModel.ClassInfo source = model == null ? null : model.getClass(from);
        Set<EdgeKind> kinds = source == null ? null : source.dependencyKinds.get(to);
        if (kinds == null && source != null) {
            kinds = source.externalDependencies.get(to);
        }
        if (kinds == null || kinds.isEmpty()) {
            return true;
        }
        for (EdgeKind kind : kinds) {
            if (hidden.stream().noneMatch(group -> group.kinds.contains(kind))) {
                return true;
            }
        }
        return false;
    }
}
