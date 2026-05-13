package de.weigend.s202.ui.consistency;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNode.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Smoke tests for the dev hook — covers the three property settings
 * (unset / warn / throw) without inspecting log output.
 */
class ArchitectureConsistencyDevHookTest {

    private static final String FLAG = "s202.dev.architectureCheck";

    @AfterEach
    void clearFlag() {
        System.clearProperty(FLAG);
    }

    @Test
    void unsetFlagSkipsTheCheckEntirely() {
        // Passing nulls would normally explode the checker — that we
        // don't even reach it proves the flag-off short-circuit works.
        assertDoesNotThrow(() -> ArchitectureConsistencyDevHook.runIfEnabled(null, null));
    }

    @Test
    void warnModeLogsButDoesNotThrowOnConsistentInputs() {
        System.setProperty(FLAG, "warn");
        DomainModel domain = consistentSingleClassDomain();
        ArchitectureNode root = consistentSingleClassUiRoot();

        assertDoesNotThrow(() -> ArchitectureConsistencyDevHook.runIfEnabled(domain, root));
    }

    @Test
    void throwModeRaisesIllegalStateOnMismatch() {
        System.setProperty(FLAG, "throw");
        DomainModel domain = consistentSingleClassDomain();
        // Mismatch: UI tree says nothing exists.
        ArchitectureNode emptyRoot = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);

        assertThrows(IllegalStateException.class,
                () -> ArchitectureConsistencyDevHook.runIfEnabled(domain, emptyRoot));
    }

    @Test
    void warnModeOnMismatchDoesNotThrow() {
        System.setProperty(FLAG, "warn");
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
        // Mirrors what ArchitectureNodeBuilder would produce for the same
        // DomainModel: a synthetic root containing "app", which contains "X".
        ArchitectureNode root = new ArchitectureNode("root", "root", NodeType.PACKAGE, true, 0);
        ArchitectureNode appPkg = new ArchitectureNode("app", "app", NodeType.PACKAGE, true, 0);
        ArchitectureNode clazz = new ArchitectureNode("app.X", "X", NodeType.CLASS, false, 0);
        appPkg.addChild(clazz);
        root.addChild(appPkg);
        return root;
    }
}
