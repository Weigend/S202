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
package de.weigend.s202.analysis.metrics;

import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The metrics of the classes of one model, and of groups of them. A value is
 * computed when it is first asked for and then kept; {@code NaN} where it
 * cannot be known, for example the complexity of classes not read from Java
 * bytecode.
 *
 * <p>A group is given by its classes, not by a package name: a package in a
 * view holds the classes the view puts into it, after moves and in planned
 * packages too.
 */
public final class MetricCatalog {

    private final String[] names;
    private final Map<String, Integer> index = new HashMap<>();
    private final DependencyModel.ClassInfo[] infos;
    private final int[][] out;
    private final int[][] in;
    private final ToIntFunction<String> level;
    private final boolean measured;
    private final Map<Metric, double[]> classValues = new EnumMap<>(Metric.class);

    private int[] component;
    private int[] componentSize;
    private BitSet[] reach;
    private BitSet[] impact;

    /**
     * @param model the classes and their dependencies
     * @param level the architecture level of a class or package, negative if unknown
     */
    public MetricCatalog(DependencyModel model, ToIntFunction<String> level) {
        this.level = level;
        List<String> sorted = new ArrayList<>(model.getAllClasses().keySet());
        sorted.sort(null);
        names = sorted.toArray(String[]::new);
        infos = new DependencyModel.ClassInfo[names.length];
        for (int i = 0; i < names.length; i++) {
            index.put(names[i], i);
            infos[i] = model.getAllClasses().get(names[i]);
        }
        out = new int[names.length][];
        List<List<Integer>> incoming = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            incoming.add(new ArrayList<>());
        }
        boolean anyMeasured = false;
        for (int i = 0; i < names.length; i++) {
            Set<Integer> targets = new HashSet<>();
            for (String target : infos[i].dependencies) {
                Integer j = index.get(target);
                if (j != null && j != i) {
                    targets.add(j);
                }
            }
            out[i] = targets.stream().mapToInt(Integer::intValue).sorted().toArray();
            for (int j : out[i]) {
                incoming.get(j).add(i);
            }
            for (DependencyModel.MethodInfo method : infos[i].methods.values()) {
                anyMeasured |= method.complexity != DependencyModel.MethodInfo.NOT_MEASURED;
            }
        }
        in = new int[names.length][];
        for (int i = 0; i < names.length; i++) {
            in[i] = incoming.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        measured = anyMeasured;
    }

    /** Whether the model has values for the metric at all; the complexity only comes from Java bytecode. */
    public boolean isAvailable(Metric metric) {
        return !metric.isFromCode() || measured;
    }

    /** The value of a class; {@code NaN} for an unknown class or a value that cannot be known. */
    public double classValue(Metric metric, String className) {
        Integer i = index.get(className);
        if (metric == Metric.LEVEL) {
            int value = level.applyAsInt(className);
            return value < 0 ? Double.NaN : value;
        }
        return i == null ? Double.NaN : classValues.computeIfAbsent(metric, this::computeClassValues)[i];
    }

    /**
     * The value of a group of classes, a package.
     *
     * @param name    the package, for its level
     * @param members its classes; unknown ones are left out
     */
    public double groupValue(Metric metric, String name, Collection<String> members) {
        if (metric == Metric.LEVEL) {
            int value = level.applyAsInt(name);
            return value < 0 ? Double.NaN : value;
        }
        if (metric.isFromCode() && !measured) {
            return Double.NaN;
        }
        BitSet group = new BitSet(names.length);
        for (String member : members) {
            Integer i = index.get(member);
            if (i != null) {
                group.set(i);
            }
        }
        int size = group.cardinality();
        if (size == 0) {
            return Double.NaN;
        }
        return switch (metric) {
            case SIZE -> size;
            case LINES, COMPLEXITY, CALLS -> sum(metric, group);
            case MAX_COMPLEXITY -> max(metric, group);
            case FAN_IN -> outside(group, in).cardinality();
            case FAN_OUT -> outside(group, out).cardinality();
            case INSTABILITY -> instability(outside(group, in).cardinality(), outside(group, out).cardinality());
            case ABSTRACTNESS -> abstractness(group, size);
            case DISTANCE -> Math.abs(abstractness(group, size)
                    + instability(outside(group, in).cardinality(), outside(group, out).cardinality()) - 1);
            case EXTERNALS -> externals(group);
            case IMPACT -> closure(group, impact()).cardinality();
            case REACH -> closure(group, reach()).cardinality();
            case CYCLE -> {
                componentsOf();
                int onCycle = 0;
                for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
                    onCycle += componentSize[component[i]] > 1 ? 1 : 0;
                }
                yield onCycle;
            }
            case LEVEL -> throw new IllegalStateException();
        };
    }

    // -----------------------------------------------------------------------

    private double[] computeClassValues(Metric metric) {
        double[] values = new double[names.length];
        if (metric.isFromCode() && !measured) {
            Arrays.fill(values, Double.NaN);
            return values;
        }
        for (int i = 0; i < names.length; i++) {
            DependencyModel.ClassInfo info = infos[i];
            values[i] = switch (metric) {
                case SIZE -> (double) info.methods.keySet().stream().filter(m -> !m.contains("lambda$")).count();
                case LINES -> info.methods.values().stream().mapToInt(m -> Math.max(0, m.lines)).sum();
                case COMPLEXITY -> info.methods.values().stream().mapToInt(m -> Math.max(0, m.complexity)).sum();
                case MAX_COMPLEXITY -> info.methods.values().stream().mapToInt(m -> m.complexity).max().orElse(0);
                case FAN_IN -> in[i].length;
                case FAN_OUT -> out[i].length;
                case INSTABILITY -> instability(in[i].length, out[i].length);
                case ABSTRACTNESS -> info.interfaceType ? 1 : 0;
                case DISTANCE -> Math.abs((info.interfaceType ? 1 : 0) + instability(in[i].length, out[i].length) - 1);
                case CALLS -> info.methods.values().stream()
                        .mapToInt(m -> m.methodCalls.values().stream().mapToInt(Integer::intValue).sum()).sum();
                case EXTERNALS -> info.externalDependencies.size();
                case IMPACT -> closure(single(i), impact()).cardinality();
                case REACH -> closure(single(i), reach()).cardinality();
                case CYCLE -> {
                    componentsOf();
                    int size = componentSize[component[i]];
                    yield size > 1 ? size : 0;
                }
                case LEVEL -> throw new IllegalStateException();
            };
        }
        return values;
    }

    private static double instability(int fanIn, int fanOut) {
        return fanIn + fanOut == 0 ? 0 : (double) fanOut / (fanIn + fanOut);
    }

    private double abstractness(BitSet group, int size) {
        int interfaces = 0;
        for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
            interfaces += infos[i].interfaceType ? 1 : 0;
        }
        return (double) interfaces / size;
    }

    private double sum(Metric metric, BitSet group) {
        double[] values = classValues.computeIfAbsent(metric, this::computeClassValues);
        double total = 0;
        for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
            total += values[i];
        }
        return total;
    }

    private double max(Metric metric, BitSet group) {
        double[] values = classValues.computeIfAbsent(metric, this::computeClassValues);
        double best = 0;
        for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
            best = Math.max(best, values[i]);
        }
        return best;
    }

    /** The classes outside the group at the other end of its edges. */
    private BitSet outside(BitSet group, int[][] edges) {
        BitSet others = new BitSet(names.length);
        for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
            for (int j : edges[i]) {
                others.set(j);
            }
        }
        others.andNot(group);
        return others;
    }

    private int externals(BitSet group) {
        Set<String> used = new HashSet<>();
        for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
            used.addAll(infos[i].externalDependencies.keySet());
        }
        return used.size();
    }

    private BitSet single(int i) {
        BitSet set = new BitSet(names.length);
        set.set(i);
        return set;
    }

    /** What the group reaches over any number of steps, without the group itself. */
    private BitSet closure(BitSet group, BitSet[] reachable) {
        BitSet all = new BitSet(names.length);
        for (int i = group.nextSetBit(0); i >= 0; i = group.nextSetBit(i + 1)) {
            all.or(reachable[i]);
        }
        all.andNot(group);
        return all;
    }

    private BitSet[] reach() {
        if (reach == null) {
            reach = closures(out);
        }
        return reach;
    }

    private BitSet[] impact() {
        if (impact == null) {
            impact = closures(in);
        }
        return impact;
    }

    /**
     * For every class what it reaches along {@code edges}. Classes on one cycle
     * reach the same; the cycles are taken together first, which leaves a graph
     * without cycles that is walked once, the reached before the reaching.
     */
    private BitSet[] closures(int[][] edges) {
        componentsOf();
        int count = componentSize.length;
        List<List<Integer>> members = new ArrayList<>(count);
        for (int c = 0; c < count; c++) {
            members.add(new ArrayList<>());
        }
        for (int i = 0; i < names.length; i++) {
            members.get(component[i]).add(i);
        }
        // Tarjan numbers the components so that one is done before every component that reaches it
        // along the edges of the model; the reverse edges need the reverse order.
        boolean forward = edges == out;
        BitSet[] byComponent = new BitSet[count];
        for (int k = 0; k < count; k++) {
            int c = forward ? k : count - 1 - k;
            BitSet reached = new BitSet(names.length);
            for (int i : members.get(c)) {
                for (int j : edges[i]) {
                    int d = component[j];
                    if (d != c) {
                        reached.or(byComponent[d]);
                    }
                    reached.set(j);
                }
            }
            byComponent[c] = reached;
        }
        BitSet[] byClass = new BitSet[names.length];
        for (int i = 0; i < names.length; i++) {
            byClass[i] = byComponent[component[i]];
        }
        return byClass;
    }

    /** The strongly connected components of the classes, numbered in the order Tarjan completes them. */
    private void componentsOf() {
        if (component != null) {
            return;
        }
        int n = names.length;
        component = new int[n];
        Arrays.fill(component, -1);
        int[] number = new int[n];
        int[] low = new int[n];
        Arrays.fill(number, -1);
        boolean[] onStack = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        List<Integer> sizes = new ArrayList<>();
        int counter = 0;
        int[] next = new int[n];
        Deque<Integer> call = new ArrayDeque<>();
        for (int root = 0; root < n; root++) {
            if (number[root] >= 0) {
                continue;
            }
            call.push(root);
            number[root] = low[root] = counter++;
            stack.push(root);
            onStack[root] = true;
            while (!call.isEmpty()) {
                int v = call.peek();
                if (next[v] < out[v].length) {
                    int w = out[v][next[v]++];
                    if (number[w] < 0) {
                        number[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        call.push(w);
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], number[w]);
                    }
                    continue;
                }
                call.pop();
                if (!call.isEmpty()) {
                    int parent = call.peek();
                    low[parent] = Math.min(low[parent], low[v]);
                }
                if (low[v] == number[v]) {
                    int id = sizes.size();
                    int size = 0;
                    int w;
                    do {
                        w = stack.pop();
                        onStack[w] = false;
                        component[w] = id;
                        size++;
                    } while (w != v);
                    sizes.add(size);
                }
            }
        }
        componentSize = sizes.stream().mapToInt(Integer::intValue).toArray();
    }
}
