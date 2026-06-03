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
package de.weigend.s202.ui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Computes and applies additive package/class scopes for a scoped
 * {@link de.weigend.s202.ui.ArchitectureView}.
 */
public final class ScopeExtensionModel {

    private ScopeExtensionModel() {
    }

    public record Candidate(String fullName, String kind, ArchitectureNode.NodeType type) {
        public String label() {
            return kind + "  " + fullName;
        }
    }

    public static List<Candidate> candidates(ArchitectureNode sourceRoot, ArchitectureNode scopeRoot) {
        Objects.requireNonNull(sourceRoot, "sourceRoot cannot be null");
        Objects.requireNonNull(scopeRoot, "scopeRoot cannot be null");

        Set<String> alreadyInScope = collectFullNames(scopeRoot);
        List<Candidate> candidates = new ArrayList<>();
        collectCandidates(sourceRoot, sourceRoot, alreadyInScope, candidates);
        candidates.sort(Comparator
                .comparing((Candidate c) -> c.type().getOrder())
                .thenComparing(Candidate::fullName, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    public static boolean addToScope(ArchitectureNode scopeRoot,
                                     ArchitectureNode sourceRoot,
                                     String fullName) {
        Objects.requireNonNull(scopeRoot, "scopeRoot cannot be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot cannot be null");
        if (fullName == null || fullName.isBlank()) {
            return false;
        }

        Set<String> alreadyInScope = collectFullNames(scopeRoot);
        if (alreadyInScope.contains(fullName)) {
            return false;
        }

        ArchitectureNode source = findNode(sourceRoot, fullName);
        if (source == null) {
            return false;
        }

        scopeRoot.addChild(ArchitectureNodeCloner.cloneTreeExcluding(source, alreadyInScope));
        return true;
    }

    static Set<String> collectFullNames(ArchitectureNode node) {
        Set<String> fullNames = new HashSet<>();
        collectFullNames(node, fullNames);
        return fullNames;
    }

    private static void collectCandidates(ArchitectureNode node,
                                          ArchitectureNode sourceRoot,
                                          Set<String> alreadyInScope,
                                          List<Candidate> candidates) {
        if (node != sourceRoot && !alreadyInScope.contains(node.getFullName())) {
            candidates.add(new Candidate(node.getFullName(), kindOf(node), node.getType()));
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectCandidates(child, sourceRoot, alreadyInScope, candidates);
        }
    }

    private static void collectFullNames(ArchitectureNode node, Set<String> fullNames) {
        fullNames.add(node.getFullName());
        for (ArchitectureNode child : node.getChildren()) {
            collectFullNames(child, fullNames);
        }
    }

    private static ArchitectureNode findNode(ArchitectureNode node, String fullName) {
        if (fullName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findNode(child, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String kindOf(ArchitectureNode node) {
        if (node.getType() == ArchitectureNode.NodeType.PACKAGE) {
            return "Package";
        }
        return node.isInterfaceType() ? "Interface" : "Class";
    }
}
