package de.weigend.s202.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

class JavaClassTest {

    private JavaClass testClass;

    @BeforeEach
    void setUp() {
        testClass = new JavaClass("com.example.MyClass");
    }

    @Test
    void testConstructor() {
        assertEquals("com.example.MyClass", testClass.getClassName());
        assertEquals("com.example", testClass.getPackageName());
        assertEquals("MyClass", testClass.getSimpleName());
    }

    @Test
    void testConstructorWithoutPackage() {
        JavaClass simpleClass = new JavaClass("MyClass");
        assertEquals("MyClass", simpleClass.getClassName());
        assertEquals("", simpleClass.getPackageName());
        assertEquals("MyClass", simpleClass.getSimpleName());
    }

    @Test
    void testConstructorNullThrows() {
        assertThrows(NullPointerException.class, () -> new JavaClass(null));
    }

    @Test
    void testAddDependency() {
        ClassDependency dep = new ClassDependency(
            "com.example.MyClass",
            "java.util.List",
            ClassDependency.DependencyType.DIRECT
        );
        testClass.addDependency(dep);

        assertEquals(1, testClass.getDependencyCount());
        assertTrue(testClass.getDependencies().contains(dep));
    }

    @Test
    void testAddMultipleDependencies() {
        ClassDependency dep1 = new ClassDependency(
            "com.example.MyClass", "java.util.List", ClassDependency.DependencyType.DIRECT);
        ClassDependency dep2 = new ClassDependency(
            "com.example.MyClass", "java.util.Map", ClassDependency.DependencyType.DIRECT);

        testClass.addDependency(dep1);
        testClass.addDependency(dep2);

        assertEquals(2, testClass.getDependencyCount());
    }

    @Test
    void testAddDependencyNullThrows() {
        assertThrows(NullPointerException.class, () -> testClass.addDependency(null));
    }

    @Test
    void testGetDependenciesReturnsNewSet() {
        ClassDependency dep = new ClassDependency(
            "com.example.MyClass", "java.util.List", ClassDependency.DependencyType.DIRECT);
        testClass.addDependency(dep);

        var deps1 = testClass.getDependencies();
        var deps2 = testClass.getDependencies();

        // Should be different instances
        assertNotSame(deps1, deps2);
        // But with same content
        assertEquals(deps1, deps2);
    }

    @Test
    void testEquality() {
        JavaClass other = new JavaClass("com.example.MyClass");
        assertEquals(testClass, other);
        assertEquals(testClass.hashCode(), other.hashCode());
    }

    @Test
    void testInequalityDifferentClassName() {
        JavaClass other = new JavaClass("com.other.MyClass");
        assertNotEquals(testClass, other);
    }
}
