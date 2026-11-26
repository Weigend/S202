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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chain ui.View -> app.Service -> core.Repo -> core.Entity, and a class
 * outside the view, out.Batch, that also uses app.Service.
 */
class TraceTest {

    @Test
    void usersAreFoundThroughOtherClasses() {
        Trace trace = Trace.of(tree(), model(), "core.Entity", Trace.Direction.USERS);

        assertEquals(Set.of("core.Repo", "app.Service", "ui.View", "out.Batch"), trace.classes(Trace.ALL));
        assertEquals(3, trace.depth(), "Entity <- Repo <- Service <- View and Batch");
    }

    @Test
    void stepsLimitHowFarItGoes() {
        Trace trace = Trace.of(tree(), model(), "core.Entity", Trace.Direction.USERS);

        assertEquals(Set.of("core.Repo"), trace.classes(1));
        assertEquals(Set.of("core.Repo", "app.Service"), trace.classes(2));
        assertEquals(1, trace.stepsTo("core.Repo"));
        assertEquals(3, trace.stepsTo("ui.View"));
        assertEquals(-1, trace.stepsTo("core.Entity"), "the start is not reached, it is where it begins");
    }

    @Test
    void dependenciesGoTheOtherWay() {
        Trace trace = Trace.of(tree(), model(), "ui.View", Trace.Direction.DEPENDENCIES);

        assertEquals(Set.of("app.Service", "core.Repo", "core.Entity"), trace.classes(Trace.ALL));
        assertEquals(3, trace.depth());
    }

    @Test
    void theViewMarksClassesAndThePackagesAroundThem() {
        Trace trace = Trace.of(tree(), model(), "core.Entity", Trace.Direction.USERS);

        assertEquals(Set.of("core", "core.Repo", "app", "app.Service", "ui", "ui.View"), trace.marked(Trace.ALL));
        assertEquals(Set.of("core", "core.Repo"), trace.marked(1));
    }

    @Test
    void whatTheViewDoesNotShowIsCounted() {
        Trace trace = Trace.of(tree(), model(), "core.Entity", Trace.Direction.USERS);

        assertEquals(1, trace.outsideView(Trace.ALL), "out.Batch");
        assertEquals(0, trace.outsideView(2));
        assertEquals(4, trace.packages(Trace.ALL));
    }

    @Test
    void aPackageStartsFromAllItsClasses() {
        Trace trace = Trace.of(tree(), model(), "core", Trace.Direction.USERS);

        assertEquals(Set.of("app.Service", "ui.View", "out.Batch"), trace.classes(Trace.ALL),
                "Repo uses Entity, but both are in core");
        assertEquals(Set.of("app", "app.Service", "ui", "ui.View"), trace.marked(Trace.ALL));
    }

    @Test
    void cyclesEndAndNothingIsReachedTwice() {
        DomainModel model = model();
        model.getClass("core.Entity").addDependency("ui.View");

        Trace trace = Trace.of(tree(), model, "core.Entity", Trace.Direction.USERS);

        assertEquals(Set.of("core.Repo", "app.Service", "ui.View", "out.Batch"), trace.classes(Trace.ALL));
    }

    @Test
    void whatTheViewDoesNotShowCannotBeAStart() {
        assertNull(Trace.of(tree(), model(), "out.Batch", Trace.Direction.USERS));
        assertNull(Trace.of(tree(), model(), "", Trace.Direction.USERS), "the root is everything");
    }

    @Test
    void nothingToFindIsATraceWithoutClasses() {
        Trace trace = Trace.of(tree(), model(), "ui.View", Trace.Direction.USERS);

        assertTrue(trace.classes(Trace.ALL).isEmpty());
        assertEquals(0, trace.depth());
        assertTrue(trace.marked(Trace.ALL).isEmpty());
    }

    @Test
    void theStripSaysWhatIsMarked() {
        Trace users = Trace.of(tree(), model(), "core.Entity", Trace.Direction.USERS);

        assertEquals("Users of Entity, transitive: 4 classes in 4 packages, 1 of them outside this view",
                TraceBar.describe(users, Trace.ALL));
        assertEquals("Users of Entity, direct: 1 class in 1 package", TraceBar.describe(users, 1));
        assertEquals("Users of Entity, up to 2 steps: 2 classes in 2 packages", TraceBar.describe(users, 2));
        assertEquals("Nothing uses View",
                TraceBar.describe(Trace.of(tree(), model(), "ui.View", Trace.Direction.USERS), Trace.ALL));
    }

    private static ArchitectureNode tree() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        for (String pkg : List.of("ui", "app", "core")) {
            root.addChild(new ArchitectureNode(pkg, pkg, ArchitectureNode.NodeType.PACKAGE, true));
        }
        for (String cls : List.of("ui.View", "app.Service", "core.Repo", "core.Entity")) {
            String pkg = cls.substring(0, cls.indexOf('.'));
            root.getChildren().stream().filter(node -> node.getFullName().equals(pkg)).findFirst().orElseThrow()
                    .addChild(new ArchitectureNode(cls, cls.substring(cls.indexOf('.') + 1),
                            ArchitectureNode.NodeType.CLASS, false));
        }
        return root;
    }

    private static DomainModel model() {
        DomainModel model = new DomainModel();
        add(model, "ui.View", "app.Service");
        add(model, "app.Service", "core.Repo", "java.util.List");
        add(model, "core.Repo", "core.Entity");
        add(model, "core.Entity");
        add(model, "out.Batch", "app.Service");
        return model;
    }

    private static void add(DomainModel model, String name, String... targets) {
        model.addClass(name, new CalculatedElementInfo(
                name, name.substring(name.indexOf('.') + 1), "CLASS", 0, new HashSet<>(Set.of(targets))));
    }
}
