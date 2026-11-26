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

import de.weigend.s202.domain.Placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A group of packages that are mutually interconnected — a
 * strongly-connected component of size {@literal >} 1 in the
 * package-level dependency graph. Tangles are reported separately
 * from {@link Violation} because they are a group-level concept
 * (member set), not an edge-level one.
 *
 * <p>Members are stored as an alphabetically sorted set so equal
 * tangles compare equal across runs.
 */
public record Tangle(Set<String> members) {

    public Tangle {
        if (members == null || members.size() < 2) {
            throw new IllegalArgumentException("a tangle must have at least 2 members");
        }
        members = java.util.Collections.unmodifiableSet(new TreeSet<>(members));
    }

    /** The container whose children the members are ("" for the root). */
    public String container() {
        return container(Placement.OF_THE_CODE);
    }

    private String container(Placement placement) {
        return placement.containerOf(members.iterator().next());
    }

    /**
     * A tangle is in scope when its container lies at or below the scope
     * package, or when the scope package is itself one of its members.
     * A {@code null} or blank scope means everything.
     */
    public boolean inScope(String packageScope) {
        return inScope(packageScope, Placement.OF_THE_CODE);
    }

    /** {@link #inScope(String)} with every element where {@code placement} says. */
    public boolean inScope(String packageScope, Placement placement) {
        if (packageScope == null || packageScope.isBlank()) {
            return true;
        }
        if (members.contains(packageScope)) {
            return true;
        }
        return placement.isIn(container(placement), packageScope);
    }

    /** Number of member names a {@link #label} spells out before it abbreviates. */
    private static final int LABEL_MEMBERS = 3;

    /**
     * Short display label for lists and view titles: {@code container: a, b, C}
     * with simple names only — for the container as well as for the members.
     * Packages are named before classes because they are the architectural
     * finding. A tangle with more than {@value #LABEL_MEMBERS} members is
     * abbreviated to {@code container: a, b, c, … (n)} with its member count.
     *
     * @param isPackage tells package members from class members
     */
    public String label(Predicate<String> isPackage) {
        return label(isPackage, Placement.OF_THE_CODE);
    }

    /** {@link #label(Predicate)} with every element where {@code placement} says. */
    public String label(Predicate<String> isPackage, Placement placement) {
        List<String> packages = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        for (String m : members) {
            (isPackage.test(m) ? packages : classes).add(simpleName(m));
        }
        List<String> names = new ArrayList<>(packages);
        names.addAll(classes);
        String shown = String.join(", ", names.subList(0, Math.min(LABEL_MEMBERS, names.size())));
        if (names.size() > LABEL_MEMBERS) {
            shown += ", … (" + names.size() + ")";
        }
        String container = container(placement);
        return (container.isEmpty() ? "<root>" : simpleName(container)) + ": " + shown;
    }

    /** The member that contains {@code fqn}: {@code fqn} itself or its nearest enclosing package member. */
    public String memberContaining(String fqn) {
        return memberContaining(members, fqn);
    }

    public static String memberContaining(Set<String> members, String fqn) {
        String current = fqn;
        while (current != null && !current.isEmpty()) {
            if (members.contains(current)) {
                return current;
            }
            current = parentOf(current);
        }
        return null;
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
