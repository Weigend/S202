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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.ContainerProjection;
import de.weigend.s202.domain.DomainComputer;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.StronglyConnectedComponent;
import de.weigend.s202.reader.DependencyModel;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Calculates the hierarchical architecture hypothesis of a code base.
 *
 * Pipeline:
 *   Step 1  Create class objects
 *   Step 2  Create package objects
 *   Step 3  Levelize every container        {@link ContainerLevelizer}: sibling graphs,
 *                                           container tangles, rank cut, localLevel
 *   Step 4  Derive class back-edges         a class dependency is a back-edge iff its
 *                                           projection onto its container was cut
 *   Step 5  Class architectureLevel         longest path on the class graph minus the level cuts:
 *                                           the back-edges that lie inside a class cycle
 *   Step 6  Package architectureLevel       longest path on the package graph derived from
 *                                           non-back-edge dependencies between different subtrees
 *   Step 7  Reverse dependencies
 *
 * The hierarchical order from Step 3 is the architecture hypothesis; it is what
 * the picture shows and what violations are measured against. It is local to
 * each container. The two architectureLevels are global: they measure how deep
 * the dependency chain below an element really is and never influence the
 * layout. For that reason the class level ignores a dependency only where a
 * class cycle forces it to — a back-edge that merely runs against the order of
 * two packages, without closing a class cycle, still counts. The package level
 * follows the same rule one level up: it ignores exactly the dependencies that
 * were cut to break a cycle between sibling subtrees.
 */
@Singleton
public class LevelCalculator implements DomainComputer {

    @Override
    public DomainModel compute(DependencyModel input) {
        return calculate(input);
    }

    public DomainModel calculate(DependencyModel rawModel) {
        DomainModel model = new DomainModel();

        // Step 1: classes. Every kind of dependency counts: without the other
        // class the code does not compile. How much it takes to get rid of it
        // is a question of the weight.
        for (String className : new TreeSet<>(rawModel.getAllClassNames())) {
            DependencyModel.ClassInfo rawClass = rawModel.getClass(className);
            model.addClass(className, new CalculatedElementInfo(
                className, rawClass.simpleName, "CLASS", 0,
                new HashSet<>(rawClass.dependencies), rawClass.interfaceType));
        }

        // Step 2: packages
        for (String packageName : new TreeSet<>(rawModel.getAllPackageNames())) {
            DependencyModel.PackageInfo rawPkg = rawModel.getPackage(packageName);
            model.addPackage(packageName, new CalculatedElementInfo(
                packageName, rawPkg.simpleName, "PACKAGE", 0, new HashSet<>()));
        }

        // Step 3: hierarchical levelization — the architecture hypothesis.
        ContainerLevelizer.Result containers = new ContainerLevelizer().levelize(model, rawModel);
        model.setSiblingEdgeWeights(containers.siblingWeights());
        model.setClassEdgeWeights(containers.classEdgeWeights());
        model.setContainerBackEdges(containers.cutSiblingEdges());
        model.setContainerTangles(containers.tangles());

        // Step 4: class back-edges by projection.
        Map<String, CalculatedElementInfo> classes = new TreeMap<>(model.getAllClasses());
        Set<String> classBackEdges = new LinkedHashSet<>();
        for (CalculatedElementInfo cls : classes.values()) {
            for (String dep : new TreeSet<>(cls.dependencies)) {
                if (!classes.containsKey(dep)) continue;
                ContainerProjection.Projection p = ContainerProjection.project(cls.fullName, dep);
                if (p != null && containers.cutSiblingEdges().contains(p.key())) {
                    classBackEdges.add(cls.fullName + "\0" + dep);
                }
            }
        }
        model.setClassBackEdges(classBackEdges);

        // Step 5: class levels. Only back-edges inside a class cycle are cut;
        // a back-edge outside any class cycle is a real acyclic dependency and
        // keeps counting towards the depth of the class. The result is a DAG:
        // every class cycle projects onto a sibling cycle in the deepest
        // container holding all its classes, so one of its own edges was cut.
        Map<String, Set<String>> classGraph = new LinkedHashMap<>();
        for (CalculatedElementInfo cls : classes.values()) {
            Set<String> targets = new TreeSet<>();
            for (String dep : cls.dependencies) {
                if (classes.containsKey(dep)) {
                    targets.add(dep);
                }
            }
            classGraph.put(cls.fullName, targets);
        }
        Map<String, Integer> classCycleOf = new HashMap<>();
        for (StronglyConnectedComponent scc : new TarjanSCCFinder().findSCCs(classGraph)) {
            if (scc.getSize() >= 2) {
                for (String member : scc.getMembers()) {
                    classCycleOf.put(member, scc.getId());
                }
            }
        }
        Set<String> classLevelCuts = new LinkedHashSet<>();
        for (String key : classBackEdges) {
            int sep = key.indexOf('\0');
            String from = key.substring(0, sep);
            String to = key.substring(sep + 1);
            Integer cycle = classCycleOf.get(from);
            if (cycle != null && cycle.equals(classCycleOf.get(to))) {
                classLevelCuts.add(key);
                classGraph.get(from).remove(to);
            }
        }
        model.setClassLevelCuts(classLevelCuts);
        ContainerLevelizer.longestPathLevels(classGraph)
            .forEach((fqn, level) -> classes.get(fqn).setArchitectureLevel(level));

        // Step 6: package levels. Package A depends on package B when a
        // non-back-edge class dependency leads from A's subtree into B's subtree
        // and neither package contains the other. This graph is acyclic because
        // every cycle in it would project onto a cycle in some container's
        // sibling DAG.
        Map<String, CalculatedElementInfo> packages = new TreeMap<>(model.getAllPackages());
        Map<String, Set<String>> packageGraph = new LinkedHashMap<>();
        for (String pkg : packages.keySet()) packageGraph.put(pkg, new TreeSet<>());
        for (CalculatedElementInfo cls : classes.values()) {
            List<String> fromAncestors = ancestorPackages(cls.fullName, packages.keySet());
            for (String dep : new TreeSet<>(cls.dependencies)) {
                if (!classes.containsKey(dep) || model.isClassBackEdge(cls.fullName, dep)) continue;
                for (String a : fromAncestors) {
                    for (String b : ancestorPackages(dep, packages.keySet())) {
                        if (!a.equals(b) && !isInSameSubtree(a, b)) {
                            packageGraph.get(a).add(b);
                        }
                    }
                }
            }
        }
        ContainerLevelizer.longestPathLevels(packageGraph)
            .forEach((fqn, level) -> packages.get(fqn).setArchitectureLevel(level));

        // Step 7: package dependencies (source side aggregated up to the target's
        // package, target side direct) and reverse dependencies for classes and packages.
        for (CalculatedElementInfo cls : classes.values()) {
            for (String dep : new TreeSet<>(cls.dependencies)) {
                CalculatedElementInfo target = classes.get(dep);
                if (target == null) continue;
                target.addDependent(cls.fullName);
                String toPkg = ContainerLevelizer.parentOf(dep);
                String ancestor = ContainerLevelizer.parentOf(cls.fullName);
                while (!ancestor.isEmpty() && packages.containsKey(ancestor) && !ancestor.equals(toPkg)) {
                    if (packages.containsKey(toPkg)) {
                        packages.get(ancestor).addDependency(toPkg);
                        packages.get(toPkg).addDependent(ancestor);
                    }
                    ancestor = ContainerLevelizer.parentOf(ancestor);
                }
            }
        }
        return model;
    }

    /** The class's package and all its ancestors that exist in the model, innermost first. */
    private static List<String> ancestorPackages(String classFqn, Set<String> knownPackages) {
        List<String> result = new ArrayList<>();
        String pkg = ContainerLevelizer.parentOf(classFqn);
        while (!pkg.isEmpty()) {
            if (knownPackages.contains(pkg)) result.add(pkg);
            pkg = ContainerLevelizer.parentOf(pkg);
        }
        return result;
    }

    private static boolean isInSameSubtree(String a, String b) {
        return a.startsWith(b + ".") || b.startsWith(a + ".");
    }
}
