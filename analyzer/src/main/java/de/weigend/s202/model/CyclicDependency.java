package de.weigend.s202.model;

import java.util.*;

/**
 * Represents a cyclic dependency between two packages.
 */
public class CyclicDependency {
    private final List<String> cycle;  // Packages in cycle order
    private final int dependencyCount; // Count of dependencies in this cycle

    public CyclicDependency(List<String> cycle, int dependencyCount) {
        this.cycle = new ArrayList<>(Objects.requireNonNull(cycle, "cycle cannot be null"));
        if (cycle.size() < 2) {
            throw new IllegalArgumentException("Cycle must have at least 2 packages");
        }
        this.dependencyCount = dependencyCount;
    }

    public List<String> getCycle() {
        return new ArrayList<>(cycle);
    }

    public int getSize() {
        return cycle.size();
    }

    public int getDependencyCount() {
        return dependencyCount;
    }

    /**
     * Returns the package that should be drawn at the bottom (has fewer dependencies).
     */
    public String getBottomPackage() {
        return cycle.get(0);
    }

    /**
     * Returns the package that should be drawn at the top.
     */
    public String getTopPackage() {
        return cycle.get(cycle.size() - 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CyclicDependency that = (CyclicDependency) o;
        return new HashSet<>(cycle).equals(new HashSet<>(that.cycle));
    }

    @Override
    public int hashCode() {
        return new HashSet<>(cycle).hashCode();
    }

    @Override
    public String toString() {
        return String.join(" -> ", cycle) + " -> " + cycle.get(0);
    }
}
