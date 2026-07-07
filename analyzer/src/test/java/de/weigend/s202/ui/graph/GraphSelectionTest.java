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
package de.weigend.s202.ui.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphSelectionTest {

    @BeforeEach
    void clearBeforeTest() {
        GraphSelection.clear();
        GraphSelection.setOnSelectionChange(null);
        GraphSelection.setOnDoubleClick(null);
    }

    @AfterEach
    void clearAfterTest() {
        GraphSelection.clear();
        GraphSelection.setOnSelectionChange(null);
        GraphSelection.setOnDoubleClick(null);
    }

    @Test
    void selectionChangesAreRoutedToTheSelectableOwner() {
        List<String> viewA = new ArrayList<>();
        List<String> viewB = new ArrayList<>();
        Consumer<String> sinkA = viewA::add;
        Consumer<String> sinkB = viewB::add;
        TestSelectable a1 = new TestSelectable("a.One", sinkA);
        TestSelectable a2 = new TestSelectable("a.Two", sinkA);
        TestSelectable b1 = new TestSelectable("b.One", sinkB);

        GraphSelection.select(a1);
        GraphSelection.select(b1);
        GraphSelection.select(a2);

        assertEquals(Arrays.asList("a.One", null, "a.Two"), viewA);
        assertEquals(Arrays.asList("b.One", null), viewB);
    }

    @Test
    void selectingAnotherNodeInTheSameOwnerDoesNotEmitAnIntermediateClear() {
        List<String> view = new ArrayList<>();
        Consumer<String> sink = view::add;
        TestSelectable first = new TestSelectable("pkg.First", sink);
        TestSelectable second = new TestSelectable("pkg.Second", sink);

        GraphSelection.select(first);
        GraphSelection.select(second);

        assertEquals(List.of("pkg.First", "pkg.Second"), view);
    }

    private record TestSelectable(String fullName,
                                  Consumer<String> selectionChangeSink) implements GraphSelection.Selectable {

        @Override
        public String getFullName() {
            return fullName;
        }

        @Override
        public void applySelectedStyle() {
        }

        @Override
        public void applyUnselectedStyle() {
        }
    }
}
