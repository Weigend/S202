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

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * What the analyzed classes use outside the analysis: the JDK, frameworks,
 * other libraries. These are the classes the reader left out on purpose and
 * those it did not get to read. A class in a package of the analysis does not
 * count, even if it was not read; that is mostly generated code.
 *
 * <p>Externals never count for levels or cycles. They are grouped by their
 * package, which stands for the library.
 */
public final class Externals {

    public static final Externals NONE = new Externals();

    private static final Map<DependencyModel, Externals> MADE = Collections.synchronizedMap(new WeakHashMap<>());

    /** Analyzed class → external class → kinds. */
    private final Map<String, Map<String, Set<EdgeKind>>> uses = new TreeMap<>();
    /** External class → the analyzed classes that use it. */
    private final Map<String, Set<String>> usedBy = new TreeMap<>();
    /** External package → its classes that are used. */
    private final Map<String, Set<String>> packages = new TreeMap<>();

    private Externals() {
    }

    /** The externals of a model; made once per model. */
    public static Externals of(DependencyModel model) {
        if (model == null) {
            return NONE;
        }
        return MADE.computeIfAbsent(model, Externals::collect);
    }

    private static Externals collect(DependencyModel model) {
        Externals result = new Externals();
        Set<String> ownPackages = new HashSet<>(model.getAllPackageNames());
        Map<String, DependencyModel.ClassInfo> classes = model.getAllClasses();
        classes.values().forEach(info -> ownPackages.add(info.packageName));
        for (DependencyModel.ClassInfo info : classes.values()) {
            for (String target : info.dependencies) {
                if (!classes.containsKey(target)) {
                    result.add(info.fullName, target, info.dependencyKinds.get(target), ownPackages);
                }
            }
            info.externalDependencies.forEach((target, kinds) ->
                    result.add(info.fullName, target, kinds, ownPackages));
        }
        return result;
    }

    private void add(String from, String to, Set<EdgeKind> kinds, Set<String> ownPackages) {
        String pkg = packageOf(to);
        if (ownPackages.contains(pkg) || to.equals(from)) {
            return;
        }
        Set<EdgeKind> known = uses.computeIfAbsent(from, k -> new TreeMap<>())
                .computeIfAbsent(to, k -> EnumSet.noneOf(EdgeKind.class));
        if (kinds != null) {
            known.addAll(kinds);
        }
        usedBy.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
        packages.computeIfAbsent(pkg, k -> new TreeSet<>()).add(to);
    }

    /** The package of a class; the default package is empty. */
    public static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    public boolean isEmpty() {
        return usedBy.isEmpty();
    }

    /** The external packages that are used, sorted, each with its classes that are used. */
    public Map<String, Set<String>> packages() {
        return Collections.unmodifiableMap(packages);
    }

    /** Whether {@code fqn} is an external class that is used. */
    public boolean isExternalClass(String fqn) {
        return usedBy.containsKey(fqn);
    }

    /** Whether {@code fqn} is an external package that is used. */
    public boolean isExternalPackage(String fqn) {
        return packages.containsKey(fqn);
    }

    /** The external classes an analyzed class uses, with the kinds. */
    public Map<String, Set<EdgeKind>> usedBy(String analyzedClass) {
        return Collections.unmodifiableMap(uses.getOrDefault(analyzedClass, Map.of()));
    }

    /** The analyzed classes that use an external class. */
    public Set<String> usersOf(String externalClass) {
        return Collections.unmodifiableSet(usedBy.getOrDefault(externalClass, Set.of()));
    }

    /** The analyzed classes that use something of an external package. */
    public Set<String> usersOfPackage(String externalPackage) {
        Set<String> result = new TreeSet<>();
        packages.getOrDefault(externalPackage, Set.of()).forEach(cls -> result.addAll(usersOf(cls)));
        return result;
    }

    /** How {@code from} uses the external class {@code to}; empty if it does not. */
    public Set<EdgeKind> kinds(String from, String to) {
        return uses.getOrDefault(from, Map.of()).getOrDefault(to, Set.of());
    }
}
