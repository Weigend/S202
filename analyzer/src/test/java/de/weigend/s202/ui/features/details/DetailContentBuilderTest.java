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
package de.weigend.s202.ui.features.details;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.EdgeCycleClassifier;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;
import de.weigend.s202.ui.core.arrows.DependencySelection;
import de.weigend.s202.ui.features.details.DetailContent.Place;
import de.weigend.s202.ui.features.details.DetailContent.Row;
import de.weigend.s202.ui.features.details.DetailContent.Style;
import io.softwareecg.wfx.lookup.api.Lookup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailContentBuilderTest {

    @BeforeAll
    static void initLookup() {
        Lookup.init();
    }

    @AfterAll
    static void shutdownLookup() {
        Lookup.shutdown();
    }

    @Test
    void aClassDependencyListsItsCallsItsOtherReferencesAndTheWayBack() {
        DetailContent content = builder(Set.of()).build(arrow("w.ViewStatus", "w.TabArea"));

        assertEquals("ViewStatus \u2192 TabArea", content.title());
        assertEquals("back-edge \u00b7 class cycle \u00b7 weight 3", content.summary());
        assertEquals(List.of(
                "CALL setDividerPositions() | getParent()  (inherited from ViewArea) | 2",
                "CALL initTab() [lambda] | remove(ViewStatus) | 1",
                "GROUP Other references (5) |  | ",
                "GROUP Cycle closes via |  | "), content.rows().stream().map(DetailContentBuilderTest::text).toList());

        Row references = content.rows().get(2);
        assertTrue(references.expanded());
        assertEquals(List.of(
                "REFERENCE field area | TabArea as field type | ",
                "REFERENCE setArea(TabArea) | TabArea as parameter type, cast target | ",
                "REFERENCE class ViewStatus | TabArea as annotation | ",
                "REFERENCE class Inner.Deep | TabArea as annotation | ",
                "REFERENCE paint() | TabArea via field TabArea.width | "),
                references.children().stream().map(DetailContentBuilderTest::text).toList());
        assertEquals(new Place("w.ViewStatus", null, null), references.children().get(0).fromPlace());
        assertEquals(new Place("w.ViewStatus", "setArea", "(Lw/TabArea;)V"), references.children().get(1).fromPlace());

        Row back = content.rows().get(3).children().get(0);
        assertEquals("EDGE TabArea | ViewStatus | 4", text(back));
        assertTrue(back.expanded(), "the only way back is what the user came for");
        assertEquals(List.of("CALL select(ViewStatus) | getTab() | 4"),
                back.children().stream().map(DetailContentBuilderTest::text).toList());
    }

    @Test
    void aCallLeadsToItsCallerAndToTheCalledMethod() {
        Row call = builder(Set.of()).build(arrow("w.ViewStatus", "w.TabArea")).rows().get(0);

        assertEquals(new Place("w.ViewStatus", "setDividerPositions", "()V"), call.fromPlace());
        assertEquals(new Place("w.TabArea", "getParent", "()Lw/ViewArea;"), call.toPlace());
    }

    @Test
    void aCutDependencySaysSoAndClosesNoCycle() {
        DetailContent content = builder(Set.of(new DependencyEdge("w.ViewStatus", "w.TabArea")))
                .build(arrow("w.ViewStatus", "w.TabArea"));

        assertEquals("back-edge \u00b7 cut in the refactoring preview \u00b7 weight 3", content.summary());
        assertTrue(content.rows().stream().noneMatch(row -> row.from().contains("closes via")));
    }

    @Test
    void anArrowBetweenPackagesListsItsClassDependenciesBackEdgesFirst() {
        DependencySelection arrow = new DependencySelection("w", "x", List.of(
                new DependencyEdge("w.TabArea", "x.Other"), new DependencyEdge("w.ViewStatus", "x.Other")));

        DetailContent content = builder(Set.of()).build(arrow);

        assertEquals("w \u2192 x", content.title());
        assertEquals("2 class dependencies \u00b7 1 back-edge \u00b7 package cycle \u00b7 weight 2", content.summary());
        assertEquals(List.of(
                "BACK_EDGE ViewStatus | Other | 1",
                "EDGE TabArea | Other | 1",
                "GROUP Package cycle closes via |  | "),
                content.rows().stream().map(DetailContentBuilderTest::text).toList());
        assertEquals(List.of("GROUP Other references (1) |  | "),
                content.rows().get(1).children().stream().map(DetailContentBuilderTest::text).toList());
        assertEquals("REFERENCE TabArea | uses Other | ",
                text(content.rows().get(1).children().get(0).children().get(0)), "no places recorded");

        Row step = content.rows().get(2).children().get(0);
        assertEquals("EDGE x | w | 1", text(step));
        assertEquals(List.of("EDGE Back | ViewArea | 1"),
                step.children().stream().map(DetailContentBuilderTest::text).toList());
    }

    @Test
    void methodsReadLikeSourceCode() {
        assertEquals("run(String[], int, Entry)".replace("Entry", "Map.Entry"),
                MethodLabels.method("p.A", "run", "([Ljava/lang/String;ILjava/util/Map$Entry;)V"));
        assertEquals("A(int)", MethodLabels.method("p.A", "<init>", "(I)V"));
        assertEquals("Inner()", MethodLabels.method("p.A", "Inner.<init>", "()V"));
        assertEquals("static initializer", MethodLabels.method("p.A", "<clinit>", "()V"));
        assertEquals("A() [lambda]", MethodLabels.method("p.A", "lambda$new$0", "(I)V"));
        assertEquals("Inner.Deep.run()", MethodLabels.method("p.A", "Inner$Deep.run", "()V"));
        assertEquals("anonymous class 1.run()", MethodLabels.method("p.A", "1.run", "()V"));
        assertEquals("Inner.anonymous class 12.run()", MethodLabels.method("p.A", "Inner$12.run", "()V"));
        assertEquals("Local.run()", MethodLabels.method("p.A", "1Local.run", "()V"));
        assertEquals("Inner() [lambda]", MethodLabels.method("p.A", "Inner.lambda$new$0", "()V"));
        assertEquals("f(self, x)", MethodLabels.method("p.A", "f", "(self, x)"), "another language");
        assertEquals("f()", MethodLabels.method("p.A", "f", null));
    }

    private static DependencySelection arrow(String from, String to) {
        return new DependencySelection(from, to, List.of(new DependencyEdge(from, to)));
    }

    private static String text(Row row) {
        return row.style() + " " + row.from() + " | " + row.to() + " | " + row.calls();
    }

    /**
     * w.ViewStatus <-> w.TabArea is a class cycle with the back-edge
     * ViewStatus -> TabArea. The packages w and x depend on each other without
     * a class cycle: w.ViewStatus -> x.Other (back-edge), w.TabArea -> x.Other,
     * x.Back -> w.ViewArea.
     */
    private static DetailContentBuilder builder(Set<DependencyEdge> cuts) {
        DependencyModel raw = new DependencyModel();
        addClass(raw, "w.ViewArea", "getParent()Lw/ViewArea;");
        DependencyModel.ClassInfo tabArea = addClass(raw, "w.TabArea",
                "remove(Lw/ViewStatus;)V", "select(Lw/ViewStatus;)V");
        DependencyModel.ClassInfo viewStatus = addClass(raw, "w.ViewStatus",
                "setDividerPositions()V", "lambda$initTab$1(Lx/Event;)V", "getTab()Lx/Tab;");
        addClass(raw, "x.Other");
        DependencyModel.ClassInfo back = addClass(raw, "x.Back");
        tabArea.addDependency("w.ViewArea", EdgeKind.EXTENDS);
        tabArea.addDependency("w.ViewStatus", EdgeKind.CALLS);
        tabArea.addDependency("x.Other", EdgeKind.USES);
        viewStatus.addDependency("w.TabArea", EdgeKind.CALLS);
        viewStatus.addDependency("w.TabArea", EdgeKind.USES);
        viewStatus.addTypeUse("w.TabArea", new TypeUse(TypeUse.Kind.FIELD, "area", null, null));
        viewStatus.addTypeUse("w.TabArea", new TypeUse(TypeUse.Kind.PARAMETER, "setArea", "(Lw/TabArea;)V", null));
        viewStatus.addTypeUse("w.TabArea", new TypeUse(TypeUse.Kind.CAST, "setArea", "(Lw/TabArea;)V", null));
        viewStatus.addTypeUse("w.TabArea", new TypeUse(TypeUse.Kind.ANNOTATION, "", null, null));
        viewStatus.addTypeUse("w.TabArea", new TypeUse(TypeUse.Kind.ANNOTATION, "Inner$Deep.", null, null));
        viewStatus.addTypeUse("w.TabArea", new TypeUse(TypeUse.Kind.FIELD_ACCESS, "paint", "()V", "TabArea.width"));
        viewStatus.addDependency("x.Other", EdgeKind.USES);
        back.addDependency("w.ViewArea", EdgeKind.USES);
        call(viewStatus, "setDividerPositions()V", "w.TabArea.getParent", 2, "()Lw/ViewArea;");
        call(viewStatus, "lambda$initTab$1(Lx/Event;)V", "w.TabArea.remove", 1, "(Lw/ViewStatus;)V");
        call(tabArea, "select(Lw/ViewStatus;)V", "w.ViewStatus.getTab", 4, "()Lx/Tab;");

        DomainModel model = new DomainModel();
        for (Map.Entry<String, DependencyModel.ClassInfo> entry : new HashMap<>(raw.getAllClasses()).entrySet()) {
            model.addClass(entry.getKey(), new DomainModel.CalculatedElementInfo(entry.getKey(),
                    entry.getValue().simpleName, "CLASS", 0, entry.getValue().dependencies));
        }
        model.setClassBackEdges(Set.of("w.ViewStatus\0w.TabArea", "w.ViewStatus\0x.Other"));
        model.setClassEdgeWeights(Map.of("w.ViewStatus\0w.TabArea", 3, "w.TabArea\0w.ViewStatus", 4));
        return new DetailContentBuilder(raw,
                EdgeCycleClassifier.of(model, cuts, Lookup.lookup(SCCFinder.class)), cuts);
    }

    private static DependencyModel.ClassInfo addClass(DependencyModel raw, String fqn, String... methods) {
        int dot = fqn.lastIndexOf('.');
        DependencyModel.ClassInfo info = new DependencyModel.ClassInfo(
                fqn, fqn.substring(dot + 1), fqn.substring(0, dot));
        for (String method : methods) {
            int paren = method.indexOf('(');
            info.addMethod(method.substring(0, paren), method.substring(paren));
        }
        raw.addClass(fqn, info);
        return info;
    }

    private static void call(DependencyModel.ClassInfo from, String caller, String call, int count, String descriptor) {
        DependencyModel.MethodInfo method = from.methods.get(caller);
        method.methodCalls.put(call, count);
        method.methodCallDescriptors.put(call, new HashSet<>(Set.of(descriptor)));
    }
}
