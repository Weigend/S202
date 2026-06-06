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
package de.weigend.s202.analysis.input;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.java.InputAnalyzer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InputAnalyzer.
 * Tests bytecode analysis, dependency extraction, and package hierarchy building.
 */
class InputAnalyzerTest {
    
    private InputAnalyzer analyzer;
    private String testJarPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new InputAnalyzer();
        // Use the test-example JAR file - resolve relative to project root
        testJarPath = "../test-example/target/test-example-1.0.0.jar";
    }

    @Test
    void testAnalyzeJarFileExists() throws IOException {
        // Verify test JAR exists
        Path jarPath = Paths.get(testJarPath);
        assertTrue(Files.exists(jarPath), "Test JAR file should exist at " + testJarPath);
    }

    @Test
    void testAnalyzeExtractsClasses() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        assertNotNull(model, "DependencyModel should not be null");
        assertTrue(model.getClassCount() > 0, "Should extract at least some classes");
        assertFalse(model.getAllClassNames().isEmpty(), "Class names set should not be empty");
    }

    @Test
    void testAnalyzeContainsExpectedClasses() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // The test-example project has com.example.A, B, C classes
        assertTrue(
            model.getAllClassNames().stream()
                .anyMatch(c -> c.startsWith("com.example")),
            "Should contain classes from com.example package"
        );
    }

    @Test
    void testAnalyzeBuildPackageHierarchy() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        assertTrue(model.getPackageCount() > 0, "Should have at least one package");
        assertFalse(model.getAllPackageNames().isEmpty(), "Package names should not be empty");
    }

    @Test
    void testAnalyzeDetectsDependencies() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Find classes with dependencies
        boolean hasDependencies = model.getAllClassNames().stream()
            .map(model::getClass)
            .anyMatch(classInfo -> !classInfo.dependencies.isEmpty());
        
        assertTrue(hasDependencies, "Should detect at least some class dependencies");
    }

    @Test
    void testAnalyzeFiltersJavaClasses() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Check that java.* and javax.* classes are filtered out
        boolean hasJavaClasses = model.getAllClassNames().stream()
            .anyMatch(className -> className.startsWith("java.") || className.startsWith("javax."));
        
        assertFalse(hasJavaClasses, "Should filter out java.* and javax.* classes");
    }

    @Test
    void testAnalyzeClassInfoStructure() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Get first class and verify structure
        String firstClass = model.getAllClassNames().iterator().next();
        DependencyModel.ClassInfo classInfo = model.getClass(firstClass);
        
        assertNotNull(classInfo, "ClassInfo should exist for class: " + firstClass);
        assertEquals(firstClass, classInfo.fullName, "Full name should match");
        assertNotNull(classInfo.simpleName, "Simple name should be set");
        assertNotNull(classInfo.packageName, "Package name should be set");
        assertNotNull(classInfo.dependencies, "Dependencies set should exist");
    }

    @Test
    void testAnalyzeSimpleNameExtraction() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Test: com.example.MyClass should have simple name MyClass
        String testClassName = model.getAllClassNames().stream()
            .filter(c -> c.contains("example"))
            .findFirst()
            .orElse(null);
        
        if (testClassName != null) {
            DependencyModel.ClassInfo classInfo = model.getClass(testClassName);
            String expectedSimpleName = testClassName.substring(testClassName.lastIndexOf('.') + 1);
            assertEquals(expectedSimpleName, classInfo.simpleName, 
                "Simple name should be extracted correctly from full name");
        }
    }

    @Test
    void testAnalyzePackageNameExtraction() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Test: com.example.MyClass should have package name com.example
        String testClassName = model.getAllClassNames().stream()
            .filter(c -> c.contains("example"))
            .findFirst()
            .orElse(null);
        
        if (testClassName != null) {
            DependencyModel.ClassInfo classInfo = model.getClass(testClassName);
            String expectedPackageName = testClassName.substring(0, testClassName.lastIndexOf('.'));
            assertEquals(expectedPackageName, classInfo.packageName,
                "Package name should be extracted correctly from full name");
        }
    }

    @Test
    void testAnalyzePackageHierarchy() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Find a package like "com.example"
        String examplePackage = model.getAllPackageNames().stream()
            .filter(p -> p.equals("com.example"))
            .findFirst()
            .orElse(null);
        
        if (examplePackage != null) {
            DependencyModel.PackageInfo pkgInfo = model.getPackage(examplePackage);
            assertNotNull(pkgInfo, "PackageInfo should exist for com.example");
            assertEquals("example", pkgInfo.simpleName, "Simple name should be 'example'");
            assertFalse(pkgInfo.classNames.isEmpty(), "Package should contain classes");
        }
    }

    @Test
    void testAnalyzeNullPathThrowsException() {
        assertThrows(NullPointerException.class, () -> analyzer.analyze((String) null),
            "Should throw exception for null path");
    }

    @Test
    void testAnalyzeNonexistentFileThrowsException() {
        assertThrows(IOException.class, () -> analyzer.analyze("/nonexistent/path/file.jar"),
            "Should throw IOException for nonexistent file");
    }

    @Test
    void testAnalyzeDependenciesAreNonNull() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Verify all classes have non-null dependencies set
        boolean allHaveDependencies = model.getAllClassNames().stream()
            .map(model::getClass)
            .allMatch(classInfo -> classInfo.dependencies != null);
        
        assertTrue(allHaveDependencies, "All ClassInfo objects should have non-null dependencies");
    }

    @Test
    void testAnalyzePackagesAreNonNull() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // Verify all packages are valid
        boolean allPackagesValid = model.getAllPackageNames().stream()
            .map(model::getPackage)
            .allMatch(pkgInfo -> pkgInfo != null 
                && pkgInfo.fullName != null 
                && pkgInfo.simpleName != null
                && pkgInfo.childPackages != null
                && pkgInfo.classNames != null);
        
        assertTrue(allPackagesValid, "All packages should be valid with non-null fields");
    }

    @Test
    void testAnalyzeReadsJavaModuleDescriptor() throws IOException {
        Path jar = tempDir.resolve("modular.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(out, "module-info.class", moduleInfoBytes());
            writeEntry(out, "com/acme/payment/PublicApi.class",
                    classBytes("com/acme/payment/PublicApi"));
            writeEntry(out, "com/acme/payment/internal/InternalService.class",
                    classBytes("com/acme/payment/internal/InternalService"));
            writeEntry(out, "com/acme/reflection/ReflectiveAdapter.class",
                    classBytes("com/acme/reflection/ReflectiveAdapter"));
        }

        DependencyModel model = analyzer.analyze(jar.toString());

        assertEquals(3, model.getClassCount());
        assertNull(model.getClass("module-info"));
        assertEquals(1, model.getModuleCount());

        DependencyModel.ModuleInfo module = model.getModule("com.acme.app");
        assertNotNull(module);
        assertTrue(model.isPackageExported("com.acme.payment"));
        assertTrue(module.exportedPackages.stream()
                .anyMatch(access -> access.packageName().equals("com.acme.payment")
                        && access.targetModules().isEmpty()));
        assertTrue(module.exportedPackages.stream()
                .anyMatch(access -> access.packageName().equals("com.acme.payment.internal")
                        && access.targetModules().equals(Set.of("com.friend"))));
        assertTrue(module.openedPackages.stream()
                .anyMatch(access -> access.packageName().equals("com.acme.reflection")));
        assertFalse(model.isPackageExported("com.acme.reflection"));
    }

    @Test
    void testAnalyzeDependencyConsistency() throws IOException {
        DependencyModel model = analyzer.analyze(testJarPath);
        
        // All dependencies should reference existing classes (or be from external packages)
        boolean dependenciesConsistent = model.getAllClassNames().stream()
            .map(model::getClass)
            .flatMap(classInfo -> classInfo.dependencies.stream())
            .allMatch(depClassName -> 
                model.getClass(depClassName) != null || 
                !depClassName.startsWith("com.example"));  // External dependencies OK
        
        assertTrue(dependenciesConsistent, "All dependencies should be consistent");
    }

    private static byte[] moduleInfoBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule("com.acme.app", 0, null);
        module.visitExport("com/acme/payment", 0);
        module.visitExport("com/acme/payment/internal", 0, "com.friend");
        module.visitOpen("com/acme/reflection", 0);
        module.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

}
