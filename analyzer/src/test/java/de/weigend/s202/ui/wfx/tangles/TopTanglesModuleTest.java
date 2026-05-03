package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.domain.DomainModel;
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
}
