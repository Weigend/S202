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
package de.weigend.s202.ui.component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Local include/exclude overrides for the component API projection.
 */
public class ComponentApiSelection {

    private final Set<String> included = new HashSet<>();
    private final Set<String> excluded = new HashSet<>();

    public void include(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return;
        }
        included.removeIf(name -> containsOrEquals(fullName, name));
        excluded.removeIf(name -> containsOrEquals(fullName, name));
        included.add(fullName);
    }

    public void exclude(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return;
        }
        included.removeIf(name -> containsOrEquals(fullName, name));
        excluded.removeIf(name -> containsOrEquals(fullName, name));
        excluded.add(fullName);
    }

    public boolean hasExplicitDecision(String fullName) {
        return explicitDecision(fullName) != null;
    }

    public Boolean explicitDecision(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        if (excluded.contains(fullName)) {
            return false;
        }
        if (included.contains(fullName)) {
            return true;
        }

        String nearest = null;
        boolean include = false;
        for (String candidate : included) {
            if (containsOrEquals(candidate, fullName)
                    && (nearest == null || candidate.length() > nearest.length())) {
                nearest = candidate;
                include = true;
            }
        }
        for (String candidate : excluded) {
            if (containsOrEquals(candidate, fullName)
                    && (nearest == null || candidate.length() > nearest.length())) {
                nearest = candidate;
                include = false;
            }
        }
        return nearest == null ? null : include;
    }

    private static boolean containsOrEquals(String parentOrElement, String childOrElement) {
        Objects.requireNonNull(parentOrElement, "parentOrElement cannot be null");
        Objects.requireNonNull(childOrElement, "childOrElement cannot be null");
        return childOrElement.equals(parentOrElement)
                || childOrElement.startsWith(parentOrElement + ".");
    }
}
