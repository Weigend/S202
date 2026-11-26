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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementSearchTest {

    private final ElementSearch search = ElementSearch.of(tree());

    @Test
    void theNameItselfComesFirstThenWhatStartsWithIt() {
        assertEquals(List.of("de.app.canvas", "de.app.canvas.CanvasView", "de.app.canvas.ArchitectureCanvas"),
                names("canvas"));
    }

    @Test
    void camelCaseInitialsFindAClass() {
        assertEquals("de.app.canvas.ArchitectureCanvas", names("AC").get(0));
        assertEquals("de.app.canvas.ArchitectureCanvas", names("ArCa").get(0));
        assertFalse(names("ac").contains("de.app.canvas.ArchitectureCanvas"), "initials need capitals");
    }

    @Test
    void withoutADotThePackagesAroundDoNotCount() {
        assertEquals(List.of("de.app"), names("app"), "Main lies in app, but is not called so");
    }

    @Test
    void withADotTheFullNameCounts() {
        assertEquals(List.of("de.app.canvas.CanvasView"), names("canvas.CanvasV"));
        assertEquals("de.app.canvas", names("app.canvas").get(0));
    }

    @Test
    void blankFindsNothingAndTheLimitHolds() {
        assertTrue(names("  ").isEmpty());
        assertEquals(1, search.find("a", 1).size());
        assertEquals(5, search.size(), "everything but the root");
    }

    private List<String> names(String query) {
        return search.find(query, 50).stream().map(ElementSearch.Hit::fullName).toList();
    }

    private static ArchitectureNode tree() {
        ArchitectureNode root = new ArchitectureNode("", "", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode app = new ArchitectureNode("de.app", "app", ArchitectureNode.NodeType.PACKAGE, true);
        ArchitectureNode canvas = new ArchitectureNode("de.app.canvas", "canvas", ArchitectureNode.NodeType.PACKAGE, true);
        root.addChild(app);
        app.addChild(canvas);
        app.addChild(new ArchitectureNode("de.app.Main", "Main", ArchitectureNode.NodeType.CLASS, false));
        canvas.addChild(new ArchitectureNode("de.app.canvas.ArchitectureCanvas", "ArchitectureCanvas",
                ArchitectureNode.NodeType.CLASS, false));
        canvas.addChild(new ArchitectureNode("de.app.canvas.CanvasView", "CanvasView",
                ArchitectureNode.NodeType.CLASS, false));
        return root;
    }
}
