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
package de.weigend.s202.ui.consistency;

import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.domain.architecture.LayeredArchitecture;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Temporary verification scaffold (C3 of ADR_ARCHITECTURE_AS_DOMAIN_CONCEPT):
 * cross-checks a {@link LayeredArchitecture} produced by the new
 * domain pipeline against the {@link ArchitectureNode} tree the existing UI
 * pipeline builds. Both consume the same {@link de.weigend.s202.domain.DomainModel},
 * so structural equivalence proves the domain model carries enough information
 * for the UI to render without additional logic.
 *
 * <p>Returns a list of {@link Discrepancy} entries — empty list means the two
 * representations agree row-by-row, element-by-element, recursively into
 * packages. The checker compares structure only (row count per package, fqn
 * set per row, type CLASS/PACKAGE, level); column order within a row is not
 * required to match since the domain side currently uses fqn-sort and the UI
 * uses a separate horizontal layout optimizer.
 *
 * <p>Removed once the UI consumes the domain architecture directly.
 */
public final class ArchitectureConsistencyChecker {

    /**
     * One reported mismatch. {@code path} points into the architecture
     * (e.g. {@code <root>/row[0]/com.example}); {@code message} explains
     * what differs.
     */
    public record Discrepancy(String path, String message) {}

    public List<Discrepancy> check(Architecture arch, ArchitectureNode uiRoot) {
        Objects.requireNonNull(arch, "arch");
        Objects.requireNonNull(uiRoot, "uiRoot");
        if (!(arch instanceof LayeredArchitecture hla)) {
            return List.of(new Discrepancy("<root>",
                    "expected LayeredArchitecture, got " + arch.getClass().getSimpleName()));
        }
        List<Discrepancy> out = new ArrayList<>();
        ArchitectureNode effective = skipTransparentPassthroughs(uiRoot);
        checkRows(hla.rows(), effective.getChildren(), "<root>", out);
        return out;
    }

    private void checkRows(List<List<Element>> archRows,
                           List<ArchitectureNode> uiChildren,
                           String path,
                           List<Discrepancy> out) {
        Map<Integer, List<ArchitectureNode>> byLevel = new TreeMap<>(Comparator.reverseOrder());
        for (ArchitectureNode child : uiChildren) {
            byLevel.computeIfAbsent(child.getLevel(), k -> new ArrayList<>()).add(child);
        }
        List<List<ArchitectureNode>> uiRows = new ArrayList<>(byLevel.values());

        if (archRows.size() != uiRows.size()) {
            out.add(new Discrepancy(path,
                    "row count mismatch: model=" + archRows.size()
                            + " ui=" + uiRows.size()));
            return;
        }
        for (int i = 0; i < archRows.size(); i++) {
            checkRow(archRows.get(i), uiRows.get(i), path + "/row[" + i + "]", out);
        }
    }

    private void checkRow(List<Element> archRow,
                          List<ArchitectureNode> uiRow,
                          String path,
                          List<Discrepancy> out) {
        Map<String, Element> archByFqn = new HashMap<>();
        for (Element e : archRow) {
            archByFqn.put(e.fqn(), e);
        }
        Map<String, ArchitectureNode> uiByFqn = new HashMap<>();
        for (ArchitectureNode n : uiRow) {
            uiByFqn.put(n.getFullName(), n);
        }

        if (!archByFqn.keySet().equals(uiByFqn.keySet())) {
            Set<String> onlyInModel = new HashSet<>(archByFqn.keySet());
            onlyInModel.removeAll(uiByFqn.keySet());
            Set<String> onlyInUi = new HashSet<>(uiByFqn.keySet());
            onlyInUi.removeAll(archByFqn.keySet());
            out.add(new Discrepancy(path,
                    "fqn set mismatch: only-in-model=" + onlyInModel
                            + " only-in-ui=" + onlyInUi));
        }

        for (String fqn : archByFqn.keySet()) {
            ArchitectureNode n = uiByFqn.get(fqn);
            if (n == null) {
                continue;
            }
            Element e = archByFqn.get(fqn);
            String childPath = path + "/" + fqn;

            if (e.localLevel() != n.getLevel()) {
                out.add(new Discrepancy(childPath,
                        "level mismatch: model=" + e.localLevel() + " ui=" + n.getLevel()));
            }
            boolean isPkg = e instanceof Element.PackageElement;
            boolean uiIsPkg = n.getType() == NodeType.PACKAGE;
            if (isPkg != uiIsPkg) {
                out.add(new Discrepancy(childPath,
                        "type mismatch: model=" + (isPkg ? "PACKAGE" : "CLASS")
                                + " ui=" + n.getType()));
                continue;
            }
            if (isPkg) {
                Element.PackageElement pe = (Element.PackageElement) e;
                checkRows(pe.rows(), n.getChildren(), childPath, out);
            }
        }
    }

    /**
     * Mirrors {@code ArchitectureTreeBuilder.shouldChildrenBeTransparent}:
     * descend through chains of single-child sub-packages without checking
     * for sibling classes. The UI uses the same rule.
     */
    private ArchitectureNode skipTransparentPassthroughs(ArchitectureNode root) {
        ArchitectureNode current = root;
        while (true) {
            long pkgCount = current.getChildren().stream()
                    .filter(c -> c.getType() == NodeType.PACKAGE)
                    .count();
            if (pkgCount != 1) {
                return current;
            }
            current = current.getChildren().stream()
                    .filter(c -> c.getType() == NodeType.PACKAGE)
                    .findFirst().orElseThrow();
        }
    }
}
