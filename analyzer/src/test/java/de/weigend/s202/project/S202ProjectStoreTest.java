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

import de.weigend.s202.project.impl.S202ProjectMapper;
import de.weigend.s202.project.impl.S202ProjectStore;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;
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
        rawModel.addComponentAnnotatedPackage("com.example");
        rawModel.addApiAnnotatedPackage("com.example.contract");
        rawModel.addPlainPackage("com.example.plain");
        ArchitectureAnnotations annotations = new ArchitectureAnnotations(
                List.of(),
                Set.of("com.example.A"),
                Set.of("com.example.internal"),
                List.of(),
                List.of())
                .withPort("com.example.A", ArchitectureAnnotations.PortDirection.INBOUND, "com.example")
                .withElementRole("com.example.internal", ArchitectureAnnotations.ElementRole.ADAPTER);
        Set<DependencyEdge> cuts = Set.of(new DependencyEdge("com.example.A", "com.example.B"));
        S202Project.Source source = new S202Project.Source(
                "JAR", List.of("/tmp/example.jar"), null);

        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        S202Project project = mapper.toProject(
                "test", source, rawModel, annotations, cuts, List.of());

        Path file = tempDir.resolve("example.s202.json");
        store.save(file, project);

        S202Project loaded = store.load(file);
        DependencyModel loadedRaw = mapper.toDependencyModel(loaded.dependencyModel());
        ArchitectureAnnotations loadedAnnotations =
                mapper.toArchitectureAnnotations(loaded.architectureAnnotations());

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

        assertEquals(Set.of("com.example"), loadedRaw.getComponentAnnotatedPackages());
        assertEquals(Set.of("com.example.contract"), loadedRaw.getApiAnnotatedPackages());
        assertEquals(Set.of("com.example.plain"), loadedRaw.getPlainPackages());
        assertEquals(Set.of("com.example.A"), loadedAnnotations.componentApiIncludes());
        assertEquals(Set.of("com.example.internal"), loadedAnnotations.componentApiExcludes());
        assertEquals(1, loadedAnnotations.ports().size());
        assertEquals("com.example.A", loadedAnnotations.ports().get(0).classFqn());
        assertEquals(ArchitectureAnnotations.PortDirection.INBOUND, loadedAnnotations.ports().get(0).direction());
        assertEquals(ArchitectureAnnotations.ElementRole.ADAPTER,
                loadedAnnotations.explicitElementRole("com.example.internal.Repository"));

        assertEquals(cuts, mapper.toPreviewCuts(loaded.previewCuts()));
    }

    /** Files older than version 7 carry computed results and no annotated packages. */
    @Test
    void whatOlderVersionsComputedIsNotRead() throws Exception {
        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        Path file = tempDir.resolve("version-six.s202.json");
        store.save(file, mapper.toProject("test",
                new S202Project.Source("JAR", List.of("/tmp/example.jar"), null), rawModel(), null));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode tree =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(file.toFile());
        tree.put("formatVersion", 6);
        tree.set("domainModel", json.readTree("{\"classes\":{},\"packages\":{}}"));
        tree.set("cycleBreakEdges", json.readTree("[{\"from\":\"a.A\",\"to\":\"a.B\"}]"));
        tree.set("layoutInvariantReport", json.readTree("{\"maxLevel\":3,\"findings\":[]}"));
        com.fasterxml.jackson.databind.node.ObjectNode raw =
                (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("dependencyModel");
        raw.remove(List.of("componentPackages", "apiPackages", "plainPackages"));
        json.writeValue(file.toFile(), tree);

        DependencyModel loaded = mapper.toDependencyModel(store.load(file).dependencyModel());

        assertEquals(1, loaded.getClassCount());
        assertTrue(loaded.getComponentAnnotatedPackages().isEmpty());
    }

    @Test
    void versionOneProjectWithoutAnnotationsLoadsWithEmptyAnnotations() throws Exception {
        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        S202Project current = mapper.toProject(
                "test",
                new S202Project.Source("JAR", List.of("/tmp/example.jar"), null),
                rawModel(),
                null);
        S202Project legacy = new S202Project(
                S202Project.FORMAT,
                1,
                current.createdWith(),
                current.source(),
                current.dependencyModel(),
                null,
                null,
                null,
                current.savedAt());

        Path file = tempDir.resolve("legacy.s202.json");
        store.save(file, legacy);

        S202Project loaded = store.load(file);

        assertEquals(1, loaded.formatVersion());
        assertEquals(ArchitectureAnnotations.empty(),
                mapper.toArchitectureAnnotations(loaded.architectureAnnotations()));
    }

    @Test
    void typeUsesRoundTripAndAreMissingInProjectsBeforeVersionSix() throws Exception {
        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        DependencyModel raw = rawModel();
        TypeUse field = new TypeUse(TypeUse.Kind.FIELD, "b", null, null);
        TypeUse access = new TypeUse(TypeUse.Kind.FIELD_ACCESS, "run", "()V", "B.x");
        raw.getClass("com.example.A").addTypeUse("com.example.B", field);
        raw.getClass("com.example.A").addTypeUse("com.example.B", access);
        Path file = tempDir.resolve("uses.s202.json");
        store.save(file, mapper.toProject("test", new S202Project.Source("JAR", List.of("/tmp/example.jar"), null),
                raw, null));

        DependencyModel loaded = mapper.toDependencyModel(store.load(file).dependencyModel());
        assertEquals(Map.of("com.example.B", Set.of(field, access)), loaded.getClass("com.example.A").typeUses);

        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode tree =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(file.toFile());
        tree.put("formatVersion", 5);
        tree.path("dependencyModel").path("classes").forEach(cls ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) cls).remove("typeUses"));
        json.writeValue(file.toFile(), tree);

        DependencyModel old = mapper.toDependencyModel(store.load(file).dependencyModel());
        assertTrue(old.getClass("com.example.A").typeUses.isEmpty());
        assertEquals(Set.of("com.example.B"), old.getClass("com.example.A").dependencies);
    }

    /** A tab saved before version 5 has no box width setting: it comes back with flush boxes. */
    @Test
    void viewSavedBeforeTheBoxWidthSettingLoadsUnlimited() throws Exception {
        S202ProjectMapper mapper = new S202ProjectMapper();
        S202ProjectStore store = new S202ProjectStore();
        S202Project current = mapper.toProject(
                "test",
                new S202Project.Source("JAR", List.of("/tmp/example.jar"), null),
                rawModel(),
                null);
        S202Project.ViewDto view = new S202Project.ViewDto(
                "LAYERED", null, null, List.of(), List.of(), null, false,
                3, true, false, false, false, true, List.of(),
                1.0, 0.0, 0.0, java.util.Map.of(), null, true);
        S202Project versionFour = new S202Project(
                S202Project.FORMAT,
                4,
                current.createdWith(),
                current.source(),
                current.dependencyModel(),
                current.architectureAnnotations(),
                List.of(),
                List.of(view),
                current.savedAt());

        Path file = tempDir.resolve("version-four.s202.json");
        store.save(file, versionFour);
        assertTrue(store.load(file).views().get(0).limitBoxWidth(), "the setting is saved with the tab");

        String json = java.nio.file.Files.readString(file);
        String withoutSetting = json.replaceAll("\\s*\"limitBoxWidth\"\\s*:\\s*true,", "");
        assertFalse(withoutSetting.contains("limitBoxWidth"));
        java.nio.file.Files.writeString(file, withoutSetting);

        S202Project.ViewDto loaded = store.load(file).views().get(0);
        assertFalse(loaded.limitBoxWidth());
        assertTrue(loaded.showDependencies(), "the rest of the tab is read as before");
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
}
