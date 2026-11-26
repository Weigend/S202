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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The files the analyzed classes were read from, side by side: which uses
 * which, and which packages are split across several of them. A file is what
 * the build made of a module, so this is the view by modules.
 */
public final class Origins {

    /**
     * One file.
     *
     * @param origin   its name; {@link #UNKNOWN} for classes of a project saved before it was recorded
     * @param classes  the classes read from it
     * @param packages the packages they are in
     * @param uses     the other files its classes depend on
     * @param usedBy   the other files whose classes depend on it
     * @param split    its packages that also have classes in another file
     */
    public record Origin(String origin, int classes, int packages, Set<String> uses, Set<String> usedBy,
                         Set<String> split) {
    }

    public static final String UNKNOWN = "(unknown)";

    private Origins() {
    }

    /** Sorted by name. */
    public static List<Origin> of(DependencyModel model) {
        Map<String, DependencyModel.ClassInfo> classes = model.getAllClasses();
        Map<String, Set<String>> classesOf = new TreeMap<>();
        Map<String, Set<String>> packagesOf = new HashMap<>();
        Map<String, Set<String>> originsOfPackage = new HashMap<>();
        Map<String, Set<String>> uses = new HashMap<>();
        Map<String, Set<String>> usedBy = new HashMap<>();
        for (DependencyModel.ClassInfo info : classes.values()) {
            String origin = originOf(info);
            classesOf.computeIfAbsent(origin, k -> new TreeSet<>()).add(info.fullName);
            packagesOf.computeIfAbsent(origin, k -> new TreeSet<>()).add(info.packageName);
            originsOfPackage.computeIfAbsent(info.packageName, k -> new TreeSet<>()).add(origin);
            for (String target : info.dependencies) {
                DependencyModel.ClassInfo other = classes.get(target);
                String otherOrigin = other == null ? null : originOf(other);
                if (otherOrigin != null && !otherOrigin.equals(origin)) {
                    uses.computeIfAbsent(origin, k -> new TreeSet<>()).add(otherOrigin);
                    usedBy.computeIfAbsent(otherOrigin, k -> new TreeSet<>()).add(origin);
                }
            }
        }
        List<Origin> result = new ArrayList<>();
        classesOf.forEach((origin, members) -> {
            Set<String> packages = packagesOf.get(origin);
            Set<String> split = new TreeSet<>();
            packages.forEach(pkg -> {
                if (originsOfPackage.get(pkg).size() > 1) {
                    split.add(pkg);
                }
            });
            result.add(new Origin(origin, members.size(), packages.size(), uses.getOrDefault(origin, Set.of()),
                    usedBy.getOrDefault(origin, Set.of()), split));
        });
        return result;
    }

    /** Where a class was read from; {@link #UNKNOWN} if that was not recorded. */
    public static String originOf(DependencyModel.ClassInfo info) {
        return info.getOrigin() == null ? UNKNOWN : info.getOrigin();
    }
}
