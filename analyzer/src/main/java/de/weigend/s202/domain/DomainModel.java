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
     * Weighted sibling graphs of all containers, keyed by source sibling fqn.
     * A container is a package (or the root) whose direct children — classes
     * and sub-packages alike — are its siblings. weight(S_A → S_B) is the
     * aggregated method-call count from classes in S_A's subtree to classes in
     * S_B's subtree (fallback 1 per class dependency without call data).
     * Because every class dependency is attributed to exactly one container
     * (the deepest common one), the map holds only sibling pairs.
     * Populated by LevelCalculator (container levelization step).
     */
    private Map<String, Map<String, Integer>> siblingEdgeWeights = new LinkedHashMap<>();

    /**
     * Weight of every class dependency that entered {@link #siblingEdgeWeights},
     * stored as "from\0to" keys: its method-call count, 1 without call data.
     * A sibling weight is the sum of the class weights projected onto it.
     */
    private Map<String, Integer> classEdgeWeights = new HashMap<>();

    /**
     * Sibling edges cut while breaking container tangles. Stored as
     * "from\0to" keys. A cut sibling edge is the hierarchical back-edge;
     * every class dependency whose projection onto its container is such an
     * edge is a {@link #classBackEdgeKeys class back-edge}.
     */
    private Set<String> containerBackEdgeKeys = new LinkedHashSet<>();

    /**
     * Class dependencies A→B whose sibling projection in the deepest common
     * container of A and B is a cut sibling edge. They are the edges that run
     * against the layout order (the violations of the picture) and the edges
     * the package level DAG ignores.
     * Stored as "from\0to" keys, analogous to {@link #containerBackEdgeKeys}.
     */
    private Set<String> classBackEdgeKeys = new LinkedHashSet<>();

    /**
     * The class back-edges that lie inside a class cycle (both endpoints in
     * the same SCC of the class graph). Only these are ignored when the class
     * {@code architectureLevel} is computed: a back-edge outside any class
     * cycle is a real, acyclic dependency and still counts towards the depth
     * of the class. Removing the level cuts makes the class graph acyclic,
     * because every class cycle contains at least one of them.
     * Always a subset of {@link #classBackEdgeKeys}.
     */
    private Set<String> classLevelCutKeys = new LinkedHashSet<>();

    /**
     * Container tangles: SCCs of size ≥ 2 in the sibling graph of one
     * container. Members are sibling fqns, i.e. classes and/or packages that
     * share the same parent. Populated by LevelCalculator.
     */
    private List<Set<String>> containerTangles = new ArrayList<>();

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

    // ===== Container graph API =====

    public void setSiblingEdgeWeights(Map<String, Map<String, Integer>> weights) {
        siblingEdgeWeights = new LinkedHashMap<>(weights);
    }

    /** Returns the weighted sibling graphs of all containers (unmodifiable). */
    public Map<String, Map<String, Integer>> getSiblingEdgeWeights() {
        return Collections.unmodifiableMap(siblingEdgeWeights);
    }

    /** Returns the weight of sibling edge S_A → S_B, or 0 if no edge exists. */
    public int getSiblingEdgeWeight(String from, String to) {
        return siblingEdgeWeights.getOrDefault(from, Map.of()).getOrDefault(to, 0);
    }

    public void setClassEdgeWeights(Map<String, Integer> weights) {
        classEdgeWeights = new HashMap<>(weights);
    }

    /** Returns the weight of class dependency A → B, or 0 if it was not weighted. */
    public int getClassEdgeWeight(String from, String to) {
        return classEdgeWeights.getOrDefault(from + "\0" + to, 0);
    }

    public void setContainerBackEdges(Set<String> backEdgeKeys) {
        containerBackEdgeKeys = new LinkedHashSet<>(backEdgeKeys);
    }

    public boolean isContainerBackEdge(String from, String to) {
        return containerBackEdgeKeys.contains(from + "\0" + to);
    }

    public Set<DependencyEdge> getContainerBackEdges() {
        return toEdges(containerBackEdgeKeys);
    }

    public void setClassBackEdges(Set<String> backEdgeKeys) {
        classBackEdgeKeys = new LinkedHashSet<>(backEdgeKeys);
    }

    public boolean isClassBackEdge(String from, String to) {
        return classBackEdgeKeys.contains(from + "\0" + to);
    }

    public int getClassBackEdgeCount() {
        return classBackEdgeKeys.size();
    }

    public Set<DependencyEdge> getClassBackEdges() {
        return toEdges(classBackEdgeKeys);
    }

    public void setClassLevelCuts(Set<String> levelCutKeys) {
        classLevelCutKeys = new LinkedHashSet<>(levelCutKeys);
    }

    /** True when A→B is ignored for the class {@code architectureLevel}: a back-edge inside a class cycle. */
    public boolean isClassLevelCut(String from, String to) {
        return classLevelCutKeys.contains(from + "\0" + to);
    }

    public int getClassLevelCutCount() {
        return classLevelCutKeys.size();
    }

    public Set<DependencyEdge> getClassLevelCuts() {
        return toEdges(classLevelCutKeys);
    }

    private static Set<DependencyEdge> toEdges(Set<String> keys) {
        Set<DependencyEdge> result = new LinkedHashSet<>();
        for (String key : keys) {
            int sep = key.indexOf('\0');
            if (sep > 0) {
                result.add(new DependencyEdge(key.substring(0, sep), key.substring(sep + 1)));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public void setContainerTangles(List<Set<String>> tangles) {
        containerTangles = new ArrayList<>(tangles);
    }

    /** All container tangles, including those whose members are classes only. */
    public List<Set<String>> getContainerTangles() {
        return Collections.unmodifiableList(containerTangles);
    }

    /**
     * Container tangles with at least one package member — the "package
     * cycles" of the orange overlay. Class-only tangles are the domain of the
     * class-SCC (purple) overlay and are left out here.
     */
    public List<Set<String>> getPackageTangles() {
        List<Set<String>> result = new ArrayList<>();
        for (Set<String> tangle : containerTangles) {
            for (String member : tangle) {
                if (packages.containsKey(member)) {
                    result.add(tangle);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
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
     * Derives from the class dependencies what else is said about them: the
     * package dependencies (source side aggregated up to the target's package,
     * target side direct) and who depends on a class or package.
     */
    public void connectPackagesAndDependents() {
        for (CalculatedElementInfo cls : classes.values()) {
            for (String dep : new TreeSet<>(cls.dependencies)) {
                CalculatedElementInfo target = classes.get(dep);
                if (target == null) continue;
                target.addDependent(cls.fullName);
                String toPkg = ContainerProjection.parentOf(dep);
                String ancestor = ContainerProjection.parentOf(cls.fullName);
                while (!ancestor.isEmpty() && packages.containsKey(ancestor) && !ancestor.equals(toPkg)) {
                    if (packages.containsKey(toPkg)) {
                        packages.get(ancestor).addDependency(toPkg);
                        packages.get(toPkg).addDependent(ancestor);
                    }
                    ancestor = ContainerProjection.parentOf(ancestor);
                }
            }
        }
    }

    /**
     * This model as if the {@code cuts} were gone from the code: without the
     * class dependencies, their weights and back-edges, and with the tangles
     * that are left. Levels and the order within the containers stay as they
     * were analyzed - a plan does not run the layout again.
     *
     * @return this model itself when nothing is cut
     */
    public DomainModel without(Set<DependencyEdge> cuts, SCCFinder finder) {
        if (cuts == null || cuts.isEmpty()) {
            return this;
        }
        DomainModel result = new DomainModel();
        Map<String, Set<String>> graph = new HashMap<>();
        for (CalculatedElementInfo cls : classes.values()) {
            Set<String> left = new HashSet<>(cls.dependencies);
            left.removeIf(dep -> cuts.contains(new DependencyEdge(cls.fullName, dep)));
            result.addClass(cls.fullName, copy(cls, left));
            graph.put(cls.fullName, left);
        }
        for (CalculatedElementInfo pkg : packages.values()) {
            result.addPackage(pkg.fullName, copy(pkg, new HashSet<>()));
        }
        result.connectPackagesAndDependents();

        Set<String> cutKeys = new HashSet<>();
        cuts.forEach(cut -> cutKeys.add(cut.from() + "\0" + cut.to()));
        result.classEdgeWeights = new HashMap<>(classEdgeWeights);
        result.classEdgeWeights.keySet().removeAll(cutKeys);
        result.classBackEdgeKeys = new LinkedHashSet<>(classBackEdgeKeys);
        result.classBackEdgeKeys.removeAll(cutKeys);
        result.classLevelCutKeys = new LinkedHashSet<>(classLevelCutKeys);
        result.classLevelCutKeys.removeAll(cutKeys);

        for (String from : new TreeSet<>(graph.keySet())) {
            for (String to : new TreeSet<>(graph.get(from))) {
                ContainerProjection.Projection p = classes.containsKey(to) ? ContainerProjection.project(from, to) : null;
                if (p == null) continue;
                result.siblingEdgeWeights.computeIfAbsent(p.fromSibling(), k -> new LinkedHashMap<>())
                        .merge(p.toSibling(), Math.max(1, getClassEdgeWeight(from, to)), Integer::sum);
                if (containerBackEdgeKeys.contains(p.key())) {
                    result.containerBackEdgeKeys.add(p.key());
                }
            }
        }
        result.containerTangles = new ArrayList<>(Placement.OF_THE_CODE.tangles(graph, finder));
        return result;
    }

    private static CalculatedElementInfo copy(CalculatedElementInfo element, Set<String> dependencies) {
        CalculatedElementInfo result = new CalculatedElementInfo(element.fullName, element.simpleName, element.type,
                element.architectureLevel, dependencies, element.interfaceType);
        result.setLocalLevel(element.localLevel);
        return result;
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
