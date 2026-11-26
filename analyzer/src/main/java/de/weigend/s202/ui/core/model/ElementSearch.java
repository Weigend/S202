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
package de.weigend.s202.ui.core.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds the classes and packages of a view by name. The best hits come first:
 * the name itself, then names that start with what was typed, then the
 * initials of a CamelCase name ({@code AC} or {@code ArCa} for
 * {@code ArchitectureCanvas}), then names that contain it. Only with a dot
 * in it is what was typed looked for in the full name as well.
 */
public final class ElementSearch {

    /** One element found. */
    public record Hit(ArchitectureNode node, int rank) {

        public String fullName() {
            return node.getFullName();
        }

        /** The package an element lies in; empty at the top. */
        public String location() {
            String full = node.getFullName();
            int dot = full.lastIndexOf('.');
            return dot < 0 ? "" : full.substring(0, dot);
        }
    }

    private static final Comparator<Hit> ORDER = Comparator.comparingInt(Hit::rank)
            .thenComparingInt(hit -> hit.node().getSimpleName().length())
            .thenComparing(Hit::fullName);

    private final List<ArchitectureNode> elements = new ArrayList<>();

    private ElementSearch(ArchitectureNode root) {
        root.getChildren().forEach(this::collect);
    }

    /** Searches what lies below {@code root}, without the root itself. */
    public static ElementSearch of(ArchitectureNode root) {
        return new ElementSearch(root);
    }

    private void collect(ArchitectureNode node) {
        elements.add(node);
        node.getChildren().forEach(this::collect);
    }

    /** The number of classes and packages there are to find. */
    public int size() {
        return elements.size();
    }

    /**
     * @param query what was typed; blank finds nothing
     * @param limit the most hits wanted
     * @return the hits, best first
     */
    public List<Hit> find(String query, int limit) {
        String typed = query == null ? "" : query.strip();
        if (typed.isEmpty()) {
            return List.of();
        }
        String lower = typed.toLowerCase(Locale.ROOT);
        Pattern initials = initials(typed);
        List<Hit> hits = new ArrayList<>();
        for (ArchitectureNode node : elements) {
            int rank = rank(node, lower, initials);
            if (rank >= 0) {
                hits.add(new Hit(node, rank));
            }
        }
        hits.sort(ORDER);
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits;
    }

    private static int rank(ArchitectureNode node, String lower, Pattern initials) {
        String simple = node.getSimpleName().toLowerCase(Locale.ROOT);
        String full = node.getFullName().toLowerCase(Locale.ROOT);
        if (simple.equals(lower) || full.equals(lower)) {
            return 0;
        }
        if (simple.startsWith(lower) || full.endsWith("." + lower)) {
            return 1;
        }
        if (initials != null && initials.matcher(node.getSimpleName()).lookingAt()) {
            return 2;
        }
        if (simple.contains(lower)) {
            return 3;
        }
        // Without a dot the name counts, not the packages it lies in: "pay"
        // would otherwise find every class of a package payment.
        if (lower.indexOf('.') >= 0 && full.contains(lower)) {
            return 4;
        }
        return -1;
    }

    /**
     * The initials pattern: every capital after the first starts a new part
     * of the name. {@code null} unless at least two capitals were typed.
     */
    private static Pattern initials(String typed) {
        if (typed.chars().filter(Character::isUpperCase).count() < 2 || typed.indexOf('.') >= 0) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                regex.append("[^A-Z]*");
            }
            regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }
}
