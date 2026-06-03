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
package de.weigend.s202.project;

import de.weigend.s202.analysis.invariants.InvariantFinding;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
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
        ArchitectureAnnotations annotations = new ArchitectureAnnotations(
                List.of(),
                Set.of("com.example.A"),
                Set.of("com.example.internal"),
                List.of(),
                List.of())
                .withPort("com.example.A", ArchitectureAnnotations.PortDirection.INBOUND, "com.example")
                .withElementRole("com.example.internal", ArchitectureAnnotations.ElementRole.ADAPTER);
        LayoutInvariantReport report = invariantReport();
        Set<DependencyEdge> cycleBreakEdges = Set.of(
                new DependencyEdge("com.example.A", "com.example.B"));
        S202Project.Source source = new S202Project.Source(
                "JAR", List.of("/tmp/example.jar"), null);

        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        S202Project project = mapper.toProject(
                "test", source, rawModel, domainModel, annotations, report, cycleBreakEdges);

        Path file = tempDir.resolve("example.s202.json");
        store.save(file, project);

        S202Project loaded = store.load(file);
        DependencyModel loadedRaw = mapper.toDependencyModel(loaded.dependencyModel());
        DomainModel loadedDomain = mapper.toDomainModel(loaded.domainModel());
        ArchitectureAnnotations loadedAnnotations =
                mapper.toArchitectureAnnotations(loaded.architectureAnnotations());
        LayoutInvariantReport loadedReport = mapper.toLayoutInvariantReport(loaded.layoutInvariantReport());
        Set<DependencyEdge> loadedEdges = mapper.toCycleBreakEdges(loaded.cycleBreakEdges());

        assertEquals(S202Project.FORMAT, loaded.format());
        assertEquals(S202Project.FORMAT_VERSION, loaded.formatVersion());
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
        assertEquals(1, loadedRaw.getModuleCount());
        DependencyModel.ModuleInfo loadedModule = loadedRaw.getModule("com.example.module");
        assertNotNull(loadedModule);
        assertTrue(loadedModule.exportedPackages.stream()
                .anyMatch(access -> access.packageName().equals("com.example")
                        && access.targetModules().isEmpty()));
        assertTrue(loadedModule.exportedPackages.stream()
                .anyMatch(access -> access.packageName().equals("com.example.internal")
                        && access.targetModules().equals(Set.of("com.friend"))));
        assertTrue(loadedModule.openedPackages.stream()
                .anyMatch(access -> access.packageName().equals("com.example.reflect")));

        DomainModel.CalculatedElementInfo loadedElement = loadedDomain.getClass("com.example.A");
        assertNotNull(loadedElement);
        assertEquals(2, loadedElement.architectureLevel);
        assertTrue(loadedElement.interfaceType);
        assertEquals(Set.of("com.example.B"), loadedElement.dependencies);
        assertEquals(Set.of("com.example.C"), loadedElement.dependents);
        assertEquals(Set.of("com.example.A"), loadedAnnotations.componentApiIncludes());
        assertEquals(Set.of("com.example.internal"), loadedAnnotations.componentApiExcludes());
        assertEquals(1, loadedAnnotations.ports().size());
        assertEquals("com.example.A", loadedAnnotations.ports().get(0).classFqn());
        assertEquals(ArchitectureAnnotations.PortDirection.INBOUND, loadedAnnotations.ports().get(0).direction());
        assertEquals(ArchitectureAnnotations.ElementRole.ADAPTER,
                loadedAnnotations.explicitElementRole("com.example.internal.Repository"));

        assertNotNull(loadedReport);
        assertEquals(1, loadedReport.findings().size());
        assertEquals("R1", loadedReport.findings().get(0).ruleId());
        assertEquals(cycleBreakEdges, loadedEdges);
    }

    @Test
    void versionOneProjectWithoutAnnotationsLoadsWithEmptyAnnotations() throws Exception {
        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        S202Project current = mapper.toProject(
                "test",
                new S202Project.Source("JAR", List.of("/tmp/example.jar"), null),
                rawModel(),
                domainModel(),
                null,
                Set.of());
        S202Project legacy = new S202Project(
                S202Project.FORMAT,
                1,
                current.createdWith(),
                current.source(),
                current.dependencyModel(),
                current.domainModel(),
                null,
                current.cycleBreakEdges(),
                current.layoutInvariantReport(),
                current.savedAt());

        Path file = tempDir.resolve("legacy.s202.json");
        store.save(file, legacy);

        S202Project loaded = store.load(file);

        assertEquals(1, loaded.formatVersion());
        assertEquals(ArchitectureAnnotations.empty(),
                mapper.toArchitectureAnnotations(loaded.architectureAnnotations()));
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

        DependencyModel.ModuleInfo moduleInfo = new DependencyModel.ModuleInfo("com.example.module", "1.0");
        moduleInfo.addExportedPackage("com.example", Set.of());
        moduleInfo.addExportedPackage("com.example.internal", Set.of("com.friend"));
        moduleInfo.addOpenedPackage("com.example.reflect", Set.of());
        model.addModule(moduleInfo);
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
