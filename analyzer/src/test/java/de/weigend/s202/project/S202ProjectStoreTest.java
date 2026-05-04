package de.weigend.s202.project;

import de.weigend.s202.analysis.invariants.InvariantFinding;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class S202ProjectStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripsAnalysisModels() throws Exception {
        DependencyModel rawModel = rawModel();
        DomainModel domainModel = domainModel();
        LayoutInvariantReport report = invariantReport();
        Set<TangleEdgeRenderer.Edge> cycleBreakEdges = Set.of(
                new TangleEdgeRenderer.Edge("com.example.A", "com.example.B"));
        S202Project.Source source = new S202Project.Source(
                "JAR", List.of("/tmp/example.jar"), null);

        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        S202Project project = mapper.toProject(
                "test", source, rawModel, domainModel, report, cycleBreakEdges);

        Path file = tempDir.resolve("example.s202.json");
        store.save(file, project);

        S202Project loaded = store.load(file);
        DependencyModel loadedRaw = mapper.toDependencyModel(loaded.dependencyModel());
        DomainModel loadedDomain = mapper.toDomainModel(loaded.domainModel());
        LayoutInvariantReport loadedReport = mapper.toLayoutInvariantReport(loaded.layoutInvariantReport());
        Set<TangleEdgeRenderer.Edge> loadedEdges = mapper.toCycleBreakEdges(loaded.cycleBreakEdges());

        assertEquals(S202Project.FORMAT, loaded.format());
        assertEquals(1, loadedRaw.getClassCount());
        DependencyModel.ClassInfo loadedClass = loadedRaw.getClass("com.example.A");
        assertNotNull(loadedClass);
        assertEquals(Set.of("com.example.B"), loadedClass.dependencies);
        assertEquals(Set.of(EdgeKind.CALLS), loadedClass.getKinds("com.example.B"));
        assertEquals(1, loadedClass.methods.size());
        assertEquals(Set.of("()V"),
                loadedClass.methods.get("run()V").methodCallDescriptors.get("com.example.B.call"));

        assertEquals(1, loadedRaw.getPackageCount());
        assertTrue(loadedRaw.getPackage("com.example").classNames.contains("com.example.A"));

        DomainModel.CalculatedElementInfo loadedElement = loadedDomain.getClass("com.example.A");
        assertNotNull(loadedElement);
        assertEquals(2, loadedElement.level);
        assertTrue(loadedElement.interfaceType);
        assertEquals(Set.of("com.example.B"), loadedElement.dependencies);
        assertEquals(Set.of("com.example.C"), loadedElement.dependents);

        assertNotNull(loadedReport);
        assertEquals(1, loadedReport.findings().size());
        assertEquals("R1", loadedReport.findings().get(0).ruleId());
        assertEquals(cycleBreakEdges, loadedEdges);
    }

    private static DependencyModel rawModel() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ClassInfo classInfo = new DependencyModel.ClassInfo(
                "com.example.A", "A", "com.example", true);
        classInfo.addDependency("com.example.B", EdgeKind.CALLS);
        DependencyModel.MethodInfo method = new DependencyModel.MethodInfo("run", "()V");
        method.methodCalls.put("com.example.B.call", 3);
        method.methodCallDescriptors.put("com.example.B.call", Set.of("()V"));
        classInfo.methods.put("run()V", method);
        model.addClass(classInfo.fullName, classInfo);

        DependencyModel.PackageInfo packageInfo = new DependencyModel.PackageInfo("com.example", "example");
        packageInfo.classNames.add("com.example.A");
        model.setPackages(Map.of(packageInfo.fullName, packageInfo));
        return model;
    }

    private static DomainModel domainModel() {
        DomainModel model = new DomainModel();
        DomainModel.CalculatedElementInfo classInfo = new DomainModel.CalculatedElementInfo(
                "com.example.A", "A", "CLASS", 2, Set.of("com.example.B"), true);
        classInfo.addDependent("com.example.C");
        model.addClass(classInfo.fullName, classInfo);
        model.addPackage("com.example", new DomainModel.CalculatedElementInfo(
                "com.example", "example", "PACKAGE", 1, Set.of("com.other")));
        return model;
    }

    private static LayoutInvariantReport invariantReport() {
        return new LayoutInvariantReport(
                List.of("/tmp/example.jar"),
                2,
                1,
                1,
                1,
                1,
                List.of(new InvariantFinding(
                        "R1",
                        "Level inversion across non-back-edge dependency",
                        "com.example.A",
                        "com.example.B",
                        2,
                        1,
                        "com.example",
                        "com.example")));
    }
}
