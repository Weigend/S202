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

import de.weigend.s202.ui.core.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TangleFilterTest {

    @Test
    void scopeIsRootedAtTheContainerSoTheMembersAreItsDirectContent() {
        ArchitectureNode scoped = TangleFilter.scope(model(), Set.of("net.app.ui", "net.app.Main"), false);

        assertEquals("net.app", scoped.getFullName(), "the namespace chain above the container is gone");
        assertEquals(List.of("net.app.Main", "net.app.ui"), names(scoped.getChildren()),
                "a tangle of one package and one class keeps both members");
    }

    @Test
    void scopeOfTwoClassesAtDifferentDepthsKeepsBothEnds() {
        ArchitectureNode scoped = TangleFilter.scope(model(), Set.of("net.app.Main", "net.app.ui.View"), false);

        assertEquals("net.app", scoped.getFullName());
        assertEquals(List.of("net.app.Main", "net.app.ui"), names(scoped.getChildren()));
        assertEquals(List.of("net.app.ui.View"), names(find(scoped, "net.app.ui").getChildren()));
    }

    @Test
    void withoutContextAPackageMemberShowsOnlyItsParticipants() {
        ArchitectureNode scoped = TangleFilter.scope(model(), Set.of("net.app.ui", "net.app.Main"), false);

        assertEquals(List.of("net.app.ui.View", "net.app.ui.dialog"), names(find(scoped, "net.app.ui").getChildren()),
                "Helper has no dependency to or from Main");
        assertEquals(List.of("net.app.ui.dialog.About"), names(find(scoped, "net.app.ui.dialog").getChildren()),
                "Wizard has none either; About is kept with its package chain");
    }

    @Test
    void withContextAPackageMemberKeepsItsWholeSubtree() {
        ArchitectureNode scoped = TangleFilter.scope(model(), Set.of("net.app.ui", "net.app.Main"), true);

        assertEquals(List.of("net.app.ui.Helper", "net.app.ui.View", "net.app.ui.dialog"),
                names(find(scoped, "net.app.ui").getChildren()));
        assertEquals(List.of("net.app.ui.dialog.About", "net.app.ui.dialog.Wizard"),
                names(find(scoped, "net.app.ui.dialog").getChildren()));
    }

    @Test
    void levelsSurviveTheFilter() {
        ArchitectureNode scoped = TangleFilter.scope(model(), Set.of("net.app.ui", "net.app.Main"), false);

        ArchitectureNode view = find(scoped, "net.app.ui.View");
        assertEquals(2, view.getLevel());
        assertEquals(7, view.getArchitectureLevel(), "the architecture level decides wrong direction and is shown on demand");
        assertEquals(7, find(TangleFilter.filter(model(), Set.of("net.app.ui.View")), "net.app.ui.View")
                .getArchitectureLevel());
    }

    @Test
    void participantsAreBothEndsOfEveryDependencyBetweenTwoMembers() {
        assertEquals(Set.of("net.app.Main", "net.app.ui.View", "net.app.ui.dialog.About"),
                TangleFilter.participants(model(), Set.of("net.app.ui", "net.app.Main")));
    }

    @Test
    void unknownMembersYieldNoTree() {
        assertNull(TangleFilter.scope(model(), Set.of("other.X", "other.Y"), false));
    }

    /**
     * net.app { Main, util { Text }, ui { View, Helper, dialog { About, Wizard } } }
     * Main -> View, About -> Main, View -> Helper, Wizard -> Helper, View -> Text.
     */
    private static ArchitectureNode model() {
        ArchitectureNode root = pkg("root");
        ArchitectureNode net = pkg("net");
        ArchitectureNode app = pkg("net.app");
        ArchitectureNode util = pkg("net.app.util");
        ArchitectureNode ui = pkg("net.app.ui");
        ArchitectureNode dialog = pkg("net.app.ui.dialog");
        ArchitectureNode view = cls("net.app.ui.View", "net.app.ui.Helper", "net.app.util.Text");
        view.setLevel(2);
        view.setArchitectureLevel(7);
        root.addChild(net);
        net.addChild(app);
        app.addChild(cls("net.app.Main", "net.app.ui.View"));
        app.addChild(util);
        app.addChild(ui);
        util.addChild(cls("net.app.util.Text"));
        ui.addChild(view);
        ui.addChild(cls("net.app.ui.Helper"));
        ui.addChild(dialog);
        dialog.addChild(cls("net.app.ui.dialog.About", "net.app.Main"));
        dialog.addChild(cls("net.app.ui.dialog.Wizard", "net.app.ui.Helper"));
        return root;
    }

    private static ArchitectureNode pkg(String fqn) {
        return new ArchitectureNode(fqn, simple(fqn), NodeType.PACKAGE, true);
    }

    private static ArchitectureNode cls(String fqn, String... dependencies) {
        ArchitectureNode node = new ArchitectureNode(fqn, simple(fqn), NodeType.CLASS, true);
        node.setDependencies(Set.of(dependencies));
        return node;
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private static List<String> names(List<ArchitectureNode> nodes) {
        return nodes.stream().map(ArchitectureNode::getFullName).sorted().toList();
    }

    private static ArchitectureNode find(ArchitectureNode node, String fqn) {
        if (node.getFullName().equals(fqn)) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = find(child, fqn);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
