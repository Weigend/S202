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
package de.weigend.s202.ui.features.between;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetweenTest {

    private final Between between = Between.of(tree(), model());

    @Test
    void betweenTwoElementsIsWhatTheFirstUsesOfTheSecond() {
        assertEquals(List.of(new DependencyEdge("a.A", "b.B"), new DependencyEdge("a.C", "b.B")),
                between.edges("a", "b"));
        assertEquals(List.of(new DependencyEdge("a.A", "b.B")), between.edges("a.A", "b.B"));
        assertTrue(between.edges("b", "a").isEmpty());
    }

    @Test
    void oneSideAloneMeansEverythingElseOnTheOther() {
        assertEquals(Set.of(new DependencyEdge("a.A", "b.B"), new DependencyEdge("a.C", "b.B")),
                Set.copyOf(between.edges("a", null)), "C -> A stays inside a");
        assertEquals(2, between.edges(null, "b.B").size());
        assertTrue(between.edges(null, null).isEmpty());
    }

    @Test
    void withinAPackageItsOwnDependenciesCount() {
        assertEquals(List.of(new DependencyEdge("a.C", "a.A")), between.edges("a", "a"));
    }

    @Test
    void whatHasNothingToDoWithASideIsKnown() {
        Set<String> used = between.usedBy("a.C");
        assertTrue(used.containsAll(Set.of("a", "a.A", "b", "b.B")), "classes and the packages around them");
        assertFalse(used.contains("b.D"));
        assertTrue(between.usersOf("b.B").containsAll(Set.of("a", "a.A", "a.C")));
        assertFalse(between.usersOf("b.B").contains("b.D"));
        assertTrue(between.usedBy(null).contains("b.D"), "without a side everything goes");
    }

    @Test
    void classesTheViewDoesNotShowDoNotCount() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        root.addChild(new ArchitectureNode("a.A", "A", ArchitectureNode.NodeType.CLASS, false));

        Between excerpt = Between.of(root, model());

        assertTrue(excerpt.edges("a.A", null).isEmpty());
        assertFalse(excerpt.knows("b.B"));
    }

    @Test
    void whatIsUsedOutsideTheAnalysisIsABranchOfItsOwn() {
        de.weigend.s202.reader.DependencyModel raw = new de.weigend.s202.reader.DependencyModel();
        de.weigend.s202.reader.DependencyModel.ClassInfo a =
                new de.weigend.s202.reader.DependencyModel.ClassInfo("a.A", "A", "a");
        a.addExternalDependency("java.util.List", de.weigend.s202.reader.EdgeKind.USES);
        raw.addClass("a.A", a);
        raw.addClass("b.B", new de.weigend.s202.reader.DependencyModel.ClassInfo("b.B", "B", "b"));

        Between withExternals = Between.of(tree(), model(), de.weigend.s202.domain.Externals.of(raw));

        ArchitectureNode external = withExternals.external();
        assertEquals(Between.EXTERNAL, external.getFullName());
        assertEquals("java.util", external.getChildren().get(0).getFullName());
        assertEquals(List.of(new DependencyEdge("a.A", "java.util.List")), withExternals.edges("a", "java.util"));
        assertEquals(List.of(new DependencyEdge("a.A", "java.util.List")),
                withExternals.edges(null, Between.EXTERNAL));
        assertTrue(withExternals.usersOf("java.util.List").contains("a"));
        assertEquals(between.edges("a", "b"), withExternals.edges("a", "b"), "inside nothing changes");
        assertEquals(null, between.external());
    }

    private static ArchitectureNode tree() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode a = new ArchitectureNode("a", "a", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode b = new ArchitectureNode("b", "b", ArchitectureNode.NodeType.PACKAGE, true);
        root.addChild(a);
        root.addChild(b);
        for (String name : List.of("a.A", "a.C")) {
            a.addChild(new ArchitectureNode(name, name.substring(2), ArchitectureNode.NodeType.CLASS, false));
        }
        for (String name : List.of("b.B", "b.D")) {
            b.addChild(new ArchitectureNode(name, name.substring(2), ArchitectureNode.NodeType.CLASS, false));
        }
        return root;
    }

    private static DomainModel model() {
        DomainModel model = new DomainModel();
        add(model, "a.A", "b.B");
        add(model, "a.C", "a.A", "b.B");
        add(model, "b.B");
        add(model, "b.D");
        return model;
    }

    private static void add(DomainModel model, String name, String... targets) {
        model.addClass(name, new CalculatedElementInfo(
                name, name.substring(2), "CLASS", 0, new HashSet<>(Set.of(targets))));
    }
}
