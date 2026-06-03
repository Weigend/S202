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
package de.weigend.s202.domain.architecture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Persistent, cross-view architecture annotations. These annotations describe
 * user-controlled architectural roles; they never modify dependency edges or
 * calculated levels.
 */
public record ArchitectureAnnotations(
        List<ComponentSpec> components,
        Set<String> componentApiIncludes,
        Set<String> componentApiExcludes,
        List<PortSpec> ports,
        List<ElementRoleMark> roles) {

    private static final ArchitectureAnnotations EMPTY =
            new ArchitectureAnnotations(List.of(), Set.of(), Set.of(), List.of(), List.of());

    public ArchitectureAnnotations {
        components = components == null ? List.of() : List.copyOf(components);
        componentApiIncludes = immutableSortedSet(componentApiIncludes);
        componentApiExcludes = immutableSortedSet(componentApiExcludes);
        ports = ports == null ? List.of() : List.copyOf(ports);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static ArchitectureAnnotations empty() {
        return EMPTY;
    }

    public ArchitectureAnnotations withComponentApiIncluded(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return this;
        }
        Set<String> includes = new LinkedHashSet<>(componentApiIncludes);
        Set<String> excludes = new LinkedHashSet<>(componentApiExcludes);
        includes.removeIf(name -> containsOrEquals(fqn, name));
        excludes.removeIf(name -> containsOrEquals(fqn, name));
        includes.add(fqn);
        return new ArchitectureAnnotations(components, includes, excludes, ports, roles);
    }

    public ArchitectureAnnotations withComponentApiExcluded(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return this;
        }
        Set<String> includes = new LinkedHashSet<>(componentApiIncludes);
        Set<String> excludes = new LinkedHashSet<>(componentApiExcludes);
        includes.removeIf(name -> containsOrEquals(fqn, name));
        excludes.removeIf(name -> containsOrEquals(fqn, name));
        excludes.add(fqn);
        return new ArchitectureAnnotations(components, includes, excludes, ports, roles);
    }

    /**
     * Returns an explicit include/exclude decision for {@code fqn}, or null
     * when heuristics should decide. Package-level annotations apply to their
     * descendants, with the nearest matching annotation winning.
     */
    public Boolean explicitComponentApiDecision(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return null;
        }
        if (componentApiExcludes.contains(fqn)) {
            return false;
        }
        if (componentApiIncludes.contains(fqn)) {
            return true;
        }

        String nearest = null;
        boolean include = false;
        for (String candidate : componentApiIncludes) {
            if (containsOrEquals(candidate, fqn)
                    && (nearest == null || candidate.length() > nearest.length())) {
                nearest = candidate;
                include = true;
            }
        }
        for (String candidate : componentApiExcludes) {
            if (containsOrEquals(candidate, fqn)
                    && (nearest == null || candidate.length() > nearest.length())) {
                nearest = candidate;
                include = false;
            }
        }
        return nearest == null ? null : include;
    }

    private static Set<String> immutableSortedSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        List<String> sorted = values.stream()
                .filter(Objects::nonNull)
                .filter(v -> !v.isBlank())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return Set.copyOf(sorted);
    }

    private static boolean containsOrEquals(String parentOrElement, String childOrElement) {
        Objects.requireNonNull(parentOrElement, "parentOrElement cannot be null");
        Objects.requireNonNull(childOrElement, "childOrElement cannot be null");
        return childOrElement.equals(parentOrElement)
                || childOrElement.startsWith(parentOrElement + ".");
    }

    public record ComponentSpec(String id, String displayName, String rootPackageFqn) {
        public ComponentSpec {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-empty");
            }
            if (rootPackageFqn == null || rootPackageFqn.isBlank()) {
                throw new IllegalArgumentException("rootPackageFqn must be non-empty");
            }
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
        }
    }

    public record PortSpec(String id,
                           String componentOrSegmentId,
                           String classFqn,
                           PortDirection direction) {
        public PortSpec {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-empty");
            }
            if (classFqn == null || classFqn.isBlank()) {
                throw new IllegalArgumentException("classFqn must be non-empty");
            }
            direction = direction == null ? PortDirection.GENERIC : direction;
        }
    }

    public enum PortDirection {
        INBOUND,
        OUTBOUND,
        GENERIC
    }

    public record ElementRoleMark(String elementFqn, ElementRole role) {
        public ElementRoleMark {
            if (elementFqn == null || elementFqn.isBlank()) {
                throw new IllegalArgumentException("elementFqn must be non-empty");
            }
            role = role == null ? ElementRole.NONE : role;
        }
    }

    public enum ElementRole {
        NONE,
        API,
        IMPLEMENTATION,
        INBOUND_PORT,
        OUTBOUND_PORT,
        ADAPTER,
        CORE
    }
}
