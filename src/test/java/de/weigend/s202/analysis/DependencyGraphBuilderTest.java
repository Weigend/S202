package de.weigend.s202.analysis;

import de.weigend.s202.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphBuilderTest {

    private DependencyGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DependencyGraphBuilder();
    }

    @Test
    void testAddClass() {
        JavaClass myClass = new JavaClass("com.example.MyClass");
        builder.addClass(myClass);

        assertTrue(builder.getAllClasses().containsKey("com.example.MyClass"));
    }

    @Test
    void testAddMultipleClasses() {
        JavaClass class1 = new JavaClass("com.example.Class1");
        JavaClass class2 = new JavaClass("com.example.Class2");

        builder.addClass(class1);
        builder.addClass(class2);

        assertEquals(2, builder.getAllClasses().size());
    }

    @Test
    void testAddClassNullThrows() {
        assertThrows(NullPointerException.class, () -> builder.addClass(null));
    }

    @Test
    void testBuildPackageHierarchy() {
        JavaClass class1 = new JavaClass("com.example.sub.Class1");
        JavaClass class2 = new JavaClass("com.example.Class2");

        builder.addClass(class1);
        builder.addClass(class2);

        JavaPackage root = builder.buildPackageHierarchy("com");

        assertNotNull(root);
        assertEquals("com", root.getPackageName());
    }

    @Test
    void testBuildPackageHierarchyEmpty() {
        JavaClass class1 = new JavaClass("com.example.Class1");
        builder.addClass(class1);

        // Try to build hierarchy for different root
        JavaPackage root = builder.buildPackageHierarchy("org");

        assertNotNull(root);
        assertEquals(0, root.getClassCount());
    }

    @Test
    void testAggregatePackageDependencies() {
        // Create classes with dependencies
        JavaClass class1 = new JavaClass("com.packageA.Class1");
        JavaClass class2 = new JavaClass("com.packageB.Class2");

        // Add dependency from A to B
        ClassDependency dep = new ClassDependency(
            "com.packageA.Class1",
            "com.packageB.Class2",
            ClassDependency.DependencyType.DIRECT
        );
        class1.addDependency(dep);

        builder.addClass(class1);
        builder.addClass(class2);

        JavaPackage root = builder.buildPackageHierarchy("com");

        // Verify package hierarchy exists
        assertNotNull(root);
    }

    @Test
    void testDetectCyclesNoCycles() {
        JavaClass class1 = new JavaClass("com.example.Class1");
        JavaClass class2 = new JavaClass("com.example.Class2");

        // One-way dependency
        ClassDependency dep = new ClassDependency(
            "com.example.Class1",
            "com.example.Class2",
            ClassDependency.DependencyType.DIRECT
        );
        class1.addDependency(dep);

        builder.addClass(class1);
        builder.addClass(class2);

        JavaPackage root = builder.buildPackageHierarchy("com");
        var cycles = builder.detectCycles(root);

        assertTrue(cycles.isEmpty());
    }
}
