package de.weigend.s202.analysis.raw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RawAnalyzer.
 * Tests bytecode analysis, dependency extraction, and package hierarchy building.
 */
class RawAnalyzerTest {
    
    private RawAnalyzer analyzer;
    private String testJarPath;

    @BeforeEach
    void setUp() {
        analyzer = new RawAnalyzer();
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
        assertThrows(NullPointerException.class, () -> analyzer.analyze(null),
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

}
