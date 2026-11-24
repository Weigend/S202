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
package de.weigend.s202.report.text.impl;

import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Look-ups on a component architecture the reports need. */
final class Components {

    private final Map<String, ComponentArchitecture.ComponentElement> byRoot = new TreeMap<>();
    private final Map<String, TreeSet<String>> publicClasses = new HashMap<>();

    Components(ComponentArchitecture architecture) {
        List<ComponentArchitecture.ComponentElement> all = architecture.allComponents();
        all.forEach(component -> byRoot.put(component.rootPackageFqn(), component));
        // innermost first: the public classes of an inner component are known by then
        List<ComponentArchitecture.ComponentElement> innermostFirst = new ArrayList<>(all);
        innermostFirst.sort(Comparator.comparingInt(
                (ComponentArchitecture.ComponentElement c) -> c.rootPackageFqn().length()).reversed());
        for (ComponentArchitecture.ComponentElement component : innermostFirst) {
            TreeSet<String> classes = new TreeSet<>();
            for (Element element : component.api()) {
                if (element instanceof Element.ClassElement) {
                    classes.add(element.fqn());
                } else {
                    classes.addAll(publicClasses.getOrDefault(element.fqn(), new TreeSet<>()));
                }
            }
            publicClasses.put(component.rootPackageFqn(), classes);
        }
    }

    Map<String, ComponentArchitecture.ComponentElement> byRoot() {
        return byRoot;
    }

    TreeSet<String> publicClasses(String rootFqn) {
        return publicClasses.getOrDefault(rootFqn, new TreeSet<>());
    }

    boolean isRoot(String fqn) {
        return byRoot.containsKey(fqn);
    }

    /** Root of the innermost component an element lies in or is the root of, {@code null} if in none. */
    String innermost(String fqn) {
        for (String current = fqn; !current.isEmpty(); current = Names.parentOf(current)) {
            if (byRoot.containsKey(current)) {
                return current;
            }
        }
        return null;
    }

    /** The outermost component that {@code source} enters and that hides {@code target}. */
    String boundaryBetween(String source, String target) {
        String outermostEntered = null;
        String outermostHiding = null;
        for (String current = Names.parentOf(target); !current.isEmpty(); current = Names.parentOf(current)) {
            if (byRoot.containsKey(current) && !source.startsWith(current + ".")) {
                outermostEntered = current;
                if (!publicClasses(current).contains(target)) {
                    outermostHiding = current;
                }
            }
        }
        return outermostHiding != null ? outermostHiding : outermostEntered;
    }
}
