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

import java.util.*;
import java.util.Collections;

/**
 * Model containing calculated level information for classes and packages.
 */
public class DomainModel {
    private final Map<String, CalculatedElementInfo> classes = new HashMap<>();
    private final Map<String, CalculatedElementInfo> packages = new HashMap<>();

    /**
     * Weighted inter-package dependency graph.
     * weight(P_A → P_B) = aggregated method-call count from classes in P_A's
     * subtree to classes in P_B, with a fallback weight of 1 for structural
     * references without method-call data.
     * Populated by LevelCalculator after package-level computation.
     */
    private Map<String, Map<String, Integer>> packageEdgeWeights = new LinkedHashMap<>();

    /**
     * Package back-edges identified and cut during SCC-breaking of the package graph.
     * Stored as "from\0to" keys. R3 excludes these from level-direction checks,
     * mirroring how R1 excludes class-level back-edges.
     */
    private Set<String> packageBackEdgeKeys = new LinkedHashSet<>();

    /**
     * Information about a calculated element (class or package) with its level.
     */
    public static class CalculatedElementInfo {
        public final String fullName;
        public final String simpleName;
        public final String type; // "CLASS" or "PACKAGE"
        public final boolean interfaceType;
        /**
         * Global architectural depth — longest dependency-chain path. Filled
         * by {@code LevelCalculator}. See ADR_ARCHITECTURE_LEVEL_VS_LOCAL_LAYER_INDEX
         * for why this is separate from any layout / rendering position.
         */
        public int architectureLevel;
        /**
         * Layout layer index within the parent container. Filled in a
         * subsequent step by the LocalLevelCalculator on the basis of
         * sibling-only dependencies. Defaults to 0.
         */
        public int localLevel;
        public final Set<String> dependencies;
        public final Set<String> dependents = new HashSet<>();

        public CalculatedElementInfo(String fullName, String simpleName, String type, int architectureLevel, Set<String> dependencies) {
            this(fullName, simpleName, type, architectureLevel, dependencies, false);
        }

        public CalculatedElementInfo(String fullName, String simpleName, String type, int architectureLevel, Set<String> dependencies,
                                     boolean interfaceType) {
            this.fullName = fullName;
            this.simpleName = simpleName;
            this.type = type;
            this.interfaceType = interfaceType;
            this.architectureLevel = architectureLevel;
            this.dependencies = dependencies;
        }

        public void setArchitectureLevel(int newLevel) {
            this.architectureLevel = newLevel;
        }

        public void setLocalLevel(int newIndex) {
            this.localLevel = newIndex;
        }

        public void addDependency(String dependency) {
            this.dependencies.add(dependency);
        }

        public void addDependent(String dependent) {
            this.dependents.add(dependent);
        }
    }

    // ===== Package graph API =====

    public void setPackageEdgeWeights(Map<String, Map<String, Integer>> weights) {
        packageEdgeWeights = new LinkedHashMap<>(weights);
    }

    public void setPackageBackEdges(Set<String> backEdgeKeys) {
        packageBackEdgeKeys = new LinkedHashSet<>(backEdgeKeys);
    }

    public boolean isPackageBackEdge(String from, String to) {
        return packageBackEdgeKeys.contains(from + "\0" + to);
    }

    /** Returns the full weighted inter-package graph (unmodifiable). */
    public Map<String, Map<String, Integer>> getPackageEdgeWeights() {
        return Collections.unmodifiableMap(packageEdgeWeights);
    }

    /** Returns the weight of edge P_A → P_B, or 0 if no edge exists. */
    public int getPackageEdgeWeight(String from, String to) {
        return packageEdgeWeights.getOrDefault(from, Map.of()).getOrDefault(to, 0);
    }

    // ===== Public API =====

    public void addClass(String className, CalculatedElementInfo classInfo) {
        classes.put(className, classInfo);
    }

    public CalculatedElementInfo getClass(String className) {
        return classes.get(className);
    }

    public Map<String, CalculatedElementInfo> getAllClasses() {
        return new HashMap<>(classes);
    }

    public void addPackage(String packageName, CalculatedElementInfo pkgInfo) {
        packages.put(packageName, pkgInfo);
    }

    public CalculatedElementInfo getPackage(String packageName) {
        return packages.get(packageName);
    }

    public Map<String, CalculatedElementInfo> getAllPackages() {
        return new HashMap<>(packages);
    }

    /**
     * Gets all elements (classes and packages) grouped by level.
     */
    public Map<Integer, List<CalculatedElementInfo>> getElementsByLevel() {
        Map<Integer, List<CalculatedElementInfo>> result = new TreeMap<>();

        for (CalculatedElementInfo classInfo : classes.values()) {
            result.computeIfAbsent(classInfo.architectureLevel, k -> new ArrayList<>()).add(classInfo);
        }

        for (CalculatedElementInfo pkgInfo : packages.values()) {
            result.computeIfAbsent(pkgInfo.architectureLevel, k -> new ArrayList<>()).add(pkgInfo);
        }

        return result;
    }

    public int getMaxLevel() {
        int max = 0;
        for (CalculatedElementInfo classInfo : classes.values()) {
            max = Math.max(max, classInfo.architectureLevel);
        }
        for (CalculatedElementInfo pkgInfo : packages.values()) {
            max = Math.max(max, pkgInfo.architectureLevel);
        }
        return max;
    }
}
