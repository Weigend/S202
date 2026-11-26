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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A horizontal slice: all packages of one depth side by side, as one graph,
 * whatever packages they lie in. At depth 2 {@code com.webshop.order.api} and
 * {@code com.webshop.payment.impl} are siblings, ordered and checked for
 * cycles against each other; in the layered view each lies in its own parent.
 *
 * <p>Like {@link PhysicalHierarchy} this is done by names: the part of a
 * package down to the depth becomes one name, its dots replaced by {@link #DOT},
 * so that {@code com.webshop.order.api.Order} becomes
 * {@code com.webshop.order‧api.Order}. The depth counts from the packages all
 * classes have in common ({@code com.webshop}); a package that is not as deep
 * keeps its name, and what lies deeper stays below its slice.
 */
public final class SliceHierarchy {

    /** Stands for the dots inside a slice: HYPHENATION POINT, which looks like a dot. */
    public static final char DOT = '‧';

    /**
     * One depth: how many packages it has and how many of them are tangled.
     *
     * @param names each tangle as its members, below the common root,
     *              biggest tangle first: {@code order ⇄ payment}
     */
    public record Level(int depth, int packages, int tangles, int tangled, List<String> names) {
    }

    private final RenamedModel renamed;
    private final int depth;

    private SliceHierarchy(DependencyModel logical, int depth) {
        this.depth = depth;
        String root = root(packagesWithClasses(logical));
        renamed = new RenamedModel(logical, info -> {
            String pkg = sliced(info.packageName, root, depth);
            return pkg.isEmpty() ? info.simpleName : pkg + "." + info.simpleName;
        });
    }

    /** The classes of {@code logical} with the packages of {@code depth} side by side; a new model. */
    public static SliceHierarchy of(DependencyModel logical, int depth) {
        return new SliceHierarchy(logical, depth);
    }

    public int depth() {
        return depth;
    }

    public DependencyModel model() {
        return renamed.model();
    }

    /** The name of a class of the analysis in this slice; other names stay as they are. */
    public String toSlice(String logical) {
        return renamed.rename(logical);
    }

    /** The name of an element of a slice in the analysis. */
    public static String toLogical(String name) {
        return name == null ? null : name.replace(DOT, '.');
    }

    /**
     * Every depth from 1 to the deepest package: how many packages it has and
     * how many tangles they form among each other. Depth 1 is what the
     * layered view shows at the top.
     */
    public static List<Level> levels(DependencyModel logical, SCCFinder finder) {
        Set<String> packages = packagesWithClasses(logical);
        String root = root(packages);
        int deepest = 0;
        for (String pkg : packages) {
            deepest = Math.max(deepest, segments(relative(pkg, root)));
        }
        List<Level> levels = new ArrayList<>();
        for (int depth = 1; depth <= deepest; depth++) {
            Map<String, Set<String>> graph = new HashMap<>();
            for (DependencyModel.ClassInfo info : logical.getAllClasses().values()) {
                String from = sliced(info.packageName, root, depth, true);
                graph.computeIfAbsent(from, k -> new HashSet<>());
                for (String target : info.dependencies) {
                    DependencyModel.ClassInfo other = logical.getClass(target);
                    if (other != null) {
                        String to = sliced(other.packageName, root, depth, true);
                        if (!to.equals(from)) {
                            graph.get(from).add(to);
                        }
                    }
                }
            }
            int tangled = 0;
            List<Set<String>> tangles = new ArrayList<>();
            for (StronglyConnectedComponent component : finder.findSCCs(graph)) {
                if (component.isTangle()) {
                    tangles.add(new TreeSet<>(component.getMembers()));
                    tangled += component.getSize();
                }
            }
            tangles.sort((a, b) -> b.size() != a.size() ? b.size() - a.size() : a.toString().compareTo(b.toString()));
            List<String> names = new ArrayList<>();
            for (Set<String> tangle : tangles) {
                List<String> members = new ArrayList<>();
                tangle.forEach(member -> members.add(toLogical(relative(member, root))));
                names.add(String.join(" \u21C4 ", members));
            }
            levels.add(new Level(depth, graph.size(), tangles.size(), tangled, List.copyOf(names)));
        }
        return levels;
    }

    /** The packages all classes lie in, down to where they part; a package with classes of its own ends it. */
    static String root(Collection<String> packages) {
        if (packages.isEmpty()) {
            return "";
        }
        String[] common = packages.iterator().next().split("\\.");
        int length = common.length;
        for (String pkg : packages) {
            String[] parts = pkg.isEmpty() ? new String[0] : pkg.split("\\.");
            int same = 0;
            while (same < Math.min(length, parts.length) && parts[same].equals(common[same])) {
                same++;
            }
            length = same;
        }
        String root = String.join(".", java.util.Arrays.copyOf(common, length));
        // Classes in the root itself would lose their place above the slice.
        return packages.contains(root) ? parentOf(root) : root;
    }

    private static String sliced(String pkg, String root, int depth) {
        return sliced(pkg, root, depth, false);
    }

    /** @param cut whether to drop what lies below the slice */
    private static String sliced(String pkg, String root, int depth, boolean cut) {
        String relative = relative(pkg, root);
        if (relative.isEmpty()) {
            return pkg;
        }
        String[] parts = relative.split("\\.");
        if (parts.length < depth) {
            return pkg;
        }
        StringBuilder slice = new StringBuilder(root);
        if (!root.isEmpty()) {
            slice.append('.');
        }
        slice.append(String.join(String.valueOf(DOT), java.util.Arrays.copyOf(parts, depth)));
        if (!cut) {
            for (int i = depth; i < parts.length; i++) {
                slice.append('.').append(parts[i]);
            }
        }
        return slice.toString();
    }

    private static String relative(String pkg, String root) {
        if (root.isEmpty()) {
            return pkg;
        }
        return pkg.equals(root) ? "" : pkg.startsWith(root + ".") ? pkg.substring(root.length() + 1) : pkg;
    }

    private static int segments(String relative) {
        return relative.isEmpty() ? 0 : relative.split("\\.").length;
    }

    private static String parentOf(String pkg) {
        int dot = pkg.lastIndexOf('.');
        return dot < 0 ? "" : pkg.substring(0, dot);
    }

    private static Set<String> packagesWithClasses(DependencyModel logical) {
        Set<String> packages = new TreeSet<>();
        logical.getAllClasses().values().forEach(info -> packages.add(info.packageName));
        return packages;
    }
}
