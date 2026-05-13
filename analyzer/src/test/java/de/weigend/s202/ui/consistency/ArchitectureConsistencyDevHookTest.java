package de.weigend.s202.ui.consistency;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Smoke tests for the always-on dev hook — verifies it tolerates nulls
 * and never throws regardless of whether the inputs agree.
 */
class ArchitectureConsistencyDevHookTest {

    @Test
    void nullInputsAreToleratedAndDoNotThrow() {
        assertDoesNotThrow(() -> ArchitectureConsistencyDevHook.runIfEnabled(null, null));
    }

    @Test
    void consistentInputsRunSilentlyToCompletion() {
        DomainModel domain = consistentSingleClassDomain();
        ArchitectureNode root = consistentSingleClassUiRoot();

        assertDoesNotThrow(() -> ArchitectureConsistencyDevHook.runIfEnabled(domain, root));
    }

    @Test
    void mismatchedInputsLogButDoNotThrow() {
        DomainModel domain = consistentSingleClassDomain();
        ArchitectureNode emptyRoot = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);

        assertDoesNotThrow(() -> ArchitectureConsistencyDevHook.runIfEnabled(domain, emptyRoot));
    }

    // ---------------------------------------------- fixtures

    private static DomainModel consistentSingleClassDomain() {
        DomainModel domain = new DomainModel();
        domain.addPackage("app", new CalculatedElementInfo("app", "app", "PACKAGE", 0, new HashSet<>()));
        domain.addClass("app.X", new CalculatedElementInfo("app.X", "X", "CLASS", 0, new HashSet<>()));
        domain.setPackageEdgeWeights(Map.of());
        domain.setPackageBackEdges(Set.of());
        return domain;
    }

    private static ArchitectureNode consistentSingleClassUiRoot() {
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        ArchitectureNode appPkg = new ArchitectureNode("app", "app", NodeType.PACKAGE, true, 0);
        ArchitectureNode clazz = new ArchitectureNode("app.X", "X", NodeType.CLASS, false, 0);
        appPkg.addChild(clazz);
        root.addChild(appPkg);
        return root;
    }
}
