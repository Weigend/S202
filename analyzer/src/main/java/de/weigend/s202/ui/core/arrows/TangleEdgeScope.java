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
package de.weigend.s202.ui.core.arrows;

import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.EdgeCycleClassifier.CycleKind;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.ui.core.model.ArchitectureNode;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * What a tangle view shows of the dependencies between its classes.
 *
 * <p>The tangle is the set of dependencies that connect two different
 * members. Dependencies inside one member belong to a deeper container and
 * are context: hidden unless {@link #setShowContext context} is switched on.
 * A {@link Highlight} pushes every tangle dependency it does not select into
 * the background.
 */
public final class TangleEdgeScope {

    /** The properties of a tangle dependency a view can emphasise. */
    public enum Highlight {
        BACK_EDGE,
        CLASS_CYCLE,
        /** On a package cycle but on no class cycle. */
        PACKAGE_CYCLE
    }

    /**
     * Number of tangle dependencies, and how many of them have each property.
     *
     * @param tangled dependencies whose two members are still tangled with
     *                each other. A dependency can lie on a class cycle without
     *                that: the cycle then runs through classes outside the tangle.
     */
    public record Stats(int edges, int backEdges, int classCycle, int packageCycle, int tangled) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);

        /** True when the cuts have broken every cycle between the members. */
        public boolean resolved() {
            return this != EMPTY && tangled == 0;
        }
    }

    private final Set<String> members;
    private final Map<String, String> memberCache = new HashMap<>();
    private final EnumSet<Highlight> highlights = EnumSet.noneOf(Highlight.class);
    private boolean showContext;

    /** @param members the tangle's members: classes and/or packages covering their subtree */
    public TangleEdgeScope(Set<String> members) {
        this.members = Set.copyOf(members);
    }

    public Set<String> members() {
        return members;
    }

    /** The member holding {@code fqn}: itself or its nearest enclosing package member; {@code null} if none. */
    public String memberOf(String fqn) {
        if (memberCache.containsKey(fqn)) {
            return memberCache.get(fqn);
        }
        String member = Tangle.memberContaining(members, fqn);
        memberCache.put(fqn, member);
        return member;
    }

    /** True when {@code from → to} connects two different members. */
    public boolean isTangleEdge(String from, String to) {
        String fromMember = memberOf(from);
        if (fromMember == null) {
            return false;
        }
        String toMember = memberOf(to);
        return toMember != null && !toMember.equals(fromMember);
    }

    /** True when the view draws {@code from → to} at all. */
    public boolean draws(String from, String to) {
        return showContext || isTangleEdge(from, to);
    }

    public boolean isShowContext() {
        return showContext;
    }

    public void setShowContext(boolean showContext) {
        this.showContext = showContext;
    }

    public Set<Highlight> highlights() {
        return highlights;
    }

    public void setHighlight(Highlight highlight, boolean on) {
        if (on) {
            highlights.add(highlight);
        } else {
            highlights.remove(highlight);
        }
    }

    /**
     * True when a highlight is active and selects none of the given
     * properties — the arrow then recedes into the background.
     */
    public boolean dims(boolean backEdge, boolean classCycle, boolean packageCycle) {
        if (highlights.isEmpty()) {
            return false;
        }
        return !(backEdge && highlights.contains(Highlight.BACK_EDGE)
                || classCycle && highlights.contains(Highlight.CLASS_CYCLE)
                || packageCycle && highlights.contains(Highlight.PACKAGE_CYCLE));
    }

    /**
     * Counts the tangle dependencies of the classes below {@code root}.
     * Dependencies the refactoring preview has cut are left out, so the
     * numbers fall as the user cuts.
     */
    public Stats stats(ArchitectureNode root, EdgeCycleClassifier classifier, BiPredicate<String, String> isCut) {
        if (root == null || classifier == null) {
            return Stats.EMPTY;
        }
        int[] counts = new int[5];
        count(root, classifier, isCut, counts);
        return new Stats(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    private void count(ArchitectureNode node, EdgeCycleClassifier classifier,
                       BiPredicate<String, String> isCut, int[] counts) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            String from = node.getFullName();
            for (String to : node.getDependencies()) {
                if (!isTangleEdge(from, to) || isCut != null && isCut.test(from, to)) {
                    continue;
                }
                counts[0]++;
                if (classifier.isBackEdge(from, to)) {
                    counts[1]++;
                }
                CycleKind kind = classifier.cycleOf(from, to);
                if (kind == CycleKind.CLASS_CYCLE) {
                    counts[2]++;
                } else if (kind == CycleKind.PACKAGE_CYCLE) {
                    counts[3]++;
                }
                if (classifier.inTangle(from, to)) {
                    counts[4]++;
                }
            }
            return;
        }
        for (ArchitectureNode child : node.getChildren()) {
            count(child, classifier, isCut, counts);
        }
    }
}
