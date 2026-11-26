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

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.domain.Externals;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.domain.StronglyConnectedComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * A dependency structure matrix: the elements of a layered architecture as rows
 * and, in the same order, as columns. A cell holds what the element of its
 * column uses of the element of its row.
 *
 * <p>The order is that of the layers, the highest first. What runs down in the
 * layers is therefore below the diagonal, and what is above it runs against
 * them. A package that is open is replaced by its members, which stay together
 * as a {@link Group}.
 */
public final class DependencyMatrix {

    /**
     * What a matrix is made of.
     *
     * @param rows    the layers, with their content
     * @param classes the classes to show; {@code null} for all
     * @param open    the packages to show the members of
     * @param model   the analyzed code
     * @param cycles  what is known about its dependencies, without the {@code cuts}
     * @param filter  the dependencies to count at all
     * @param cuts      the dependencies that count as gone
     * @param externals what is used outside the analysis, shown as one row per
     *                  package below the rest; {@link Externals#NONE} for no such rows
     */
    public record Input(List<List<Element>> rows, Set<String> classes, Set<String> open, DomainModel model,
                        EdgeCycleClassifier cycles, BiPredicate<String, String> filter, Set<DependencyEdge> cuts,
                        Externals externals) {

        /** Without externals. */
        public Input(List<List<Element>> rows, Set<String> classes, Set<String> open, DomainModel model,
                     EdgeCycleClassifier cycles, BiPredicate<String, String> filter, Set<DependencyEdge> cuts) {
            this(rows, classes, open, model, cycles, filter, cuts, Externals.NONE);
        }
    }

    /**
     * One row and column.
     *
     * @param external a package outside the analysis; it only has a row that
     *                 is used, its column stays empty
     */
    public record Item(String fqn, boolean isPackage, boolean external) {

        public Item(String fqn, boolean isPackage) {
            this(fqn, isPackage, false);
        }
    }

    /**
     * The members of an open package, from row {@code first} to {@code last}.
     *
     * @param depth 0 for the outermost open package
     */
    public record Group(String fqn, int first, int last, int depth) {
    }

    /**
     * What the element of the column uses of the element of the row.
     *
     * @param edges         the class dependencies, without those that are cut
     * @param weight        the calls that carry them
     * @param againstLayers how many of them run against the layers
     * @param cycle         the strongest cycle one of them lies on
     * @param cut           the class dependencies that are cut
     */
    public record Cell(List<DependencyEdge> edges, int weight, int againstLayers, CycleKind cycle,
                       List<DependencyEdge> cut) {

        /** Everything the cell stands for, cut or not. */
        public List<DependencyEdge> allEdges() {
            List<DependencyEdge> all = new ArrayList<>(edges);
            all.addAll(cut);
            return all;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Integer> indexOf = new HashMap<>();
    private final Map<Long, Cell> cells = new HashMap<>();
    private final Set<String> packages = new LinkedHashSet<>();
    private final Set<String> packagesOfPackages = new LinkedHashSet<>();
    private int dependencies;
    private int tangles;

    private DependencyMatrix() {
    }

    public static DependencyMatrix of(Input input, SCCFinder finder) {
        return new Builder(input).build(finder);
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    public int size() {
        return items.size();
    }

    /** Outer packages before the ones in them. */
    public List<Group> groups() {
        return Collections.unmodifiableList(groups);
    }

    /** What the element of {@code column} uses of that of {@code row}; {@code null} for nothing. */
    public Cell cell(int row, int column) {
        return cells.get(key(row, column));
    }

    /** The row of an element, or of the closed package it is in; -1 if there is none. */
    public int indexOf(String fqn) {
        return indexOf.getOrDefault(fqn, -1);
    }

    /** All packages in the matrix, open or not: those that can be opened and closed. */
    public Set<String> packages() {
        return Collections.unmodifiableSet(packages);
    }

    /** Those {@link #packages()} that directly hold other packages. */
    public Set<String> packagesOfPackages() {
        return Collections.unmodifiableSet(packagesOfPackages);
    }

    /** How many cells hold a dependency that is not cut. */
    public int dependencies() {
        return dependencies;
    }

    /** How many groups of rows depend on each other in circles. */
    public int tangles() {
        return tangles;
    }

    private static long key(int row, int column) {
        return (long) row << 32 | column;
    }

    private static final class Builder {

        private final Input input;
        private final DependencyMatrix matrix = new DependencyMatrix();
        private final Map<String, Integer> itemOfClass = new HashMap<>();
        /** The rows a class is in, from the outermost container to the class. */
        private final Map<String, int[]> positions = new HashMap<>();

        private Builder(Input input) {
            this.input = input;
        }

        private DependencyMatrix build(SCCFinder finder) {
            List<List<Element>> rows = shownOf(input.rows());
            // A single package says nothing; its members do.
            while (rows.size() == 1 && rows.get(0).size() == 1
                    && rows.get(0).get(0) instanceof Element.PackageElement only) {
                rows = only.rows();
            }
            addItems(rows, 0);
            locate(rows, new int[0], -1);
            Map<Long, List<DependencyEdge>> external = addExternals();
            addCells(external);
            countTangles(finder);
            return matrix;
        }

        /** The rows without what is not to be shown, and without packages that are empty then. */
        private List<List<Element>> shownOf(List<List<Element>> rows) {
            List<List<Element>> result = new ArrayList<>();
            for (List<Element> row : rows) {
                List<Element> shown = new ArrayList<>();
                for (Element element : row) {
                    if (element instanceof Element.PackageElement pkg) {
                        List<List<Element>> content = shownOf(pkg.rows());
                        if (!content.isEmpty()) {
                            shown.add(new Element.PackageElement(
                                    pkg.fqn(), pkg.architectureLevel(), pkg.localLevel(), content));
                        }
                    } else if (input.classes() == null || input.classes().contains(element.fqn())) {
                        shown.add(element);
                    }
                }
                if (!shown.isEmpty()) {
                    result.add(shown);
                }
            }
            return result;
        }

        private void addItems(List<List<Element>> rows, int depth) {
            for (List<Element> row : rows) {
                for (Element element : row) {
                    if (element instanceof Element.PackageElement pkg) {
                        addPackage(pkg);
                        if (input.open().contains(pkg.fqn())) {
                            int first = matrix.items.size();
                            int group = matrix.groups.size();
                            addItems(pkg.rows(), depth + 1);
                            matrix.groups.add(group, new Group(pkg.fqn(), first, matrix.items.size() - 1, depth));
                            continue;
                        }
                        addPackagesBelow(pkg);
                    }
                    matrix.indexOf.put(element.fqn(), matrix.items.size());
                    matrix.items.add(new Item(element.fqn(), element instanceof Element.PackageElement));
                }
            }
        }

        private void addPackage(Element.PackageElement pkg) {
            matrix.packages.add(pkg.fqn());
            if (pkg.rows().stream().flatMap(List::stream).anyMatch(Element.PackageElement.class::isInstance)) {
                matrix.packagesOfPackages.add(pkg.fqn());
            }
        }

        private void addPackagesBelow(Element.PackageElement pkg) {
            for (List<Element> row : pkg.rows()) {
                for (Element element : row) {
                    if (element instanceof Element.PackageElement inner) {
                        addPackage(inner);
                        addPackagesBelow(inner);
                    }
                }
            }
        }

        /** @param item the closed package around the rows; -1 if there is none */
        private void locate(List<List<Element>> rows, int[] prefix, int item) {
            for (int row = 0; row < rows.size(); row++) {
                int[] position = java.util.Arrays.copyOf(prefix, prefix.length + 1);
                position[prefix.length] = row;
                for (Element element : rows.get(row)) {
                    int own = item >= 0 ? item : matrix.indexOf(element.fqn());
                    if (own >= 0) {
                        matrix.indexOf.putIfAbsent(element.fqn(), own);
                    }
                    if (element instanceof Element.PackageElement pkg) {
                        locate(pkg.rows(), position, own);
                    } else {
                        itemOfClass.put(element.fqn(), own);
                        positions.put(element.fqn(), position);
                    }
                }
            }
        }

        /**
         * One row per external package that is used by a class shown, below
         * everything else: what lies outside is used, but uses nothing.
         *
         * @return the class dependencies per cell
         */
        private Map<Long, List<DependencyEdge>> addExternals() {
            Map<Long, List<DependencyEdge>> result = new LinkedHashMap<>();
            Externals externals = input.externals() == null ? Externals.NONE : input.externals();
            if (externals.isEmpty()) {
                return result;
            }
            Map<String, Integer> rowOfPackage = new java.util.TreeMap<>();
            Map<String, List<DependencyEdge>> edgesOfPackage = new HashMap<>();
            for (String from : itemOfClass.keySet().stream().sorted().toList()) {
                for (String to : externals.usedBy(from).keySet()) {
                    if (input.filter().test(from, to)) {
                        edgesOfPackage.computeIfAbsent(Externals.packageOf(to), k -> new ArrayList<>())
                                .add(new DependencyEdge(from, to));
                    }
                }
            }
            for (String pkg : new java.util.TreeSet<>(edgesOfPackage.keySet())) {
                int row = matrix.items.size();
                rowOfPackage.put(pkg, row);
                matrix.indexOf.put(pkg, row);
                matrix.items.add(new Item(pkg, true, true));
            }
            edgesOfPackage.forEach((pkg, edges) -> {
                int row = rowOfPackage.get(pkg);
                for (DependencyEdge edge : edges) {
                    matrix.indexOf.putIfAbsent(edge.to(), row);
                    result.computeIfAbsent(key(row, itemOfClass.get(edge.from())), k -> new ArrayList<>()).add(edge);
                }
            });
            return result;
        }

        private void addCells(Map<Long, List<DependencyEdge>> external) {
            Map<Long, List<DependencyEdge>> left = new LinkedHashMap<>();
            Map<Long, List<DependencyEdge>> cut = new LinkedHashMap<>();
            for (String from : itemOfClass.keySet().stream().sorted().toList()) {
                CalculatedElementInfo source = input.model().getClass(from);
                if (source == null) {
                    continue;
                }
                int column = itemOfClass.get(from);
                for (String to : source.dependencies.stream().sorted().toList()) {
                    Integer row = itemOfClass.get(to);
                    if (row == null || row == column || !input.filter().test(from, to)) {
                        continue;
                    }
                    DependencyEdge edge = new DependencyEdge(from, to);
                    (input.cuts().contains(edge) ? cut : left)
                            .computeIfAbsent(key(row, column), k -> new ArrayList<>()).add(edge);
                }
            }
            Set<Long> keys = new LinkedHashSet<>(left.keySet());
            keys.addAll(cut.keySet());
            for (Long key : keys) {
                matrix.cells.put(key, cell(left.getOrDefault(key, List.of()), cut.getOrDefault(key, List.of())));
            }
            // Outside the analysis there are no layers, no cycles and no calls that are counted.
            external.forEach((key, edges) -> matrix.cells.merge(key,
                    new Cell(List.copyOf(edges), edges.size(), 0, CycleKind.NONE, List.of()),
                    (inside, outside) -> inside));
            matrix.dependencies = left.size() + external.size();
        }

        private Cell cell(List<DependencyEdge> edges, List<DependencyEdge> cut) {
            int weight = 0;
            int against = 0;
            CycleKind cycle = CycleKind.NONE;
            for (DependencyEdge edge : edges) {
                weight += input.cycles().weightOf(edge.from(), edge.to());
                if (runsAgainstLayers(edge)) {
                    against++;
                }
                CycleKind kind = input.cycles().cycleOf(edge.from(), edge.to());
                if (kind.compareTo(cycle) > 0) {
                    cycle = kind;
                }
            }
            return new Cell(List.copyOf(edges), weight, against, cycle, List.copyOf(cut));
        }

        /**
         * Upwards in the layers; within one row a back-edge, which is the
         * reason why both ends are in one row.
         */
        private boolean runsAgainstLayers(DependencyEdge edge) {
            int[] from = positions.get(edge.from());
            int[] to = positions.get(edge.to());
            for (int i = 0; i < Math.min(from.length, to.length); i++) {
                if (from[i] != to[i]) {
                    return from[i] > to[i];
                }
            }
            return input.cycles().isBackEdge(edge.from(), edge.to());
        }

        private void countTangles(SCCFinder finder) {
            Map<String, Set<String>> graph = new LinkedHashMap<>();
            matrix.items.forEach(item -> graph.put(item.fqn(), new LinkedHashSet<>()));
            matrix.cells.forEach((key, cell) -> {
                if (!cell.edges().isEmpty()) {
                    String from = matrix.items.get((int) (key & 0xffffffffL)).fqn();
                    String to = matrix.items.get((int) (key >> 32)).fqn();
                    graph.get(from).add(to);
                }
            });
            matrix.tangles = (int) finder.findSCCs(graph).stream()
                    .filter(StronglyConnectedComponent::isTangle).count();
        }
    }
}
