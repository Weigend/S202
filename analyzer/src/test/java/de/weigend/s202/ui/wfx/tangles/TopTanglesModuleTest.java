package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopTanglesModuleTest {

    @Test
    void computeTopTanglesMarksCycleBreakEdges() {
        DomainModel model = new DomainModel();
        model.addClass("a.A", new DomainModel.CalculatedElementInfo(
                "a.A", "A", "CLASS", 2, Set.of("a.B")));
        model.addClass("a.B", new DomainModel.CalculatedElementInfo(
                "a.B", "B", "CLASS", 1, Set.of("a.C")));
        model.addClass("a.C", new DomainModel.CalculatedElementInfo(
                "a.C", "C", "CLASS", 0, Set.of("a.A")));

        var tangles = TopTanglesModule.computeTopTangles(
                model, null, Set.of(new TangleEdgeRenderer.Edge("a.C", "a.A")), null, 5);

        assertEquals(1, tangles.size());
        assertTrue(tangles.get(0).edges().stream()
                .anyMatch(edge -> edge.from().equals("a.C")
                        && edge.to().equals("a.A")
                        && edge.cycleBreakEdge()));
    }

    @Test
    void computeTopTanglesRemovesAppliedCutEdgesFromSccGraph() {
        DomainModel model = new DomainModel();
        model.addClass("a.A", new DomainModel.CalculatedElementInfo(
                "a.A", "A", "CLASS", 2, Set.of("a.B")));
        model.addClass("a.B", new DomainModel.CalculatedElementInfo(
                "a.B", "B", "CLASS", 1, Set.of("a.C")));
        model.addClass("a.C", new DomainModel.CalculatedElementInfo(
                "a.C", "C", "CLASS", 0, Set.of("a.A")));

        var cutEdge = new TangleEdgeRenderer.Edge("a.C", "a.A");
        var tangles = TopTanglesModule.computeTopTangles(
                model, null, Set.of(cutEdge), Set.of(cutEdge), null, 5);

        assertTrue(tangles.isEmpty());
    }

    @Test
    void computeTopTanglesShowsCalledMethodNames() {
        DomainModel model = new DomainModel();
        model.addClass("a.A", new DomainModel.CalculatedElementInfo(
                "a.A", "A", "CLASS", 2, Set.of("a.B")));
        model.addClass("a.B", new DomainModel.CalculatedElementInfo(
                "a.B", "B", "CLASS", 1, Set.of("a.A")));

        DependencyModel rawModel = new DependencyModel();
        DependencyModel.ClassInfo a = new DependencyModel.ClassInfo("a.A", "A", "a");
        a.addDependency("a.B", EdgeKind.CALLS);
        DependencyModel.MethodInfo source = new DependencyModel.MethodInfo("source", "()V");
        source.methodCalls.put("a.B.work", 1);
        source.methodCallDescriptors.put("a.B.work", Set.of("(I)V"));
        a.methods.put("source()V", source);
        DependencyModel.ClassInfo b = new DependencyModel.ClassInfo("a.B", "B", "a");
        b.addDependency("a.A", EdgeKind.CALLS);
        rawModel.addClass("a.A", a);
        rawModel.addClass("a.B", b);

        var tangles = TopTanglesModule.computeTopTangles(
                model, rawModel, Set.of(), null, 5);

        assertEquals(1, tangles.size());
        assertTrue(tangles.get(0).edges().stream()
                .filter(edge -> edge.from().equals("a.A") && edge.to().equals("a.B"))
                .flatMap(edge -> edge.entries().stream())
                .anyMatch(entry -> entry.kind() == EdgeKind.CALLS && "work(I)V".equals(entry.detail())));
    }
}
