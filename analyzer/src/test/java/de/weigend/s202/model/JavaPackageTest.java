package de.weigend.s202.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

class JavaPackageTest {

    private JavaPackage testPackage;

    @BeforeEach
    void setUp() {
        testPackage = new JavaPackage("com.example");
    }

    @Test
    void testConstructor() {
        assertEquals("com.example", testPackage.getPackageName());
        assertEquals("example", testPackage.getSimpleName());
    }

    @Test
    void testConstructorEmpty() {
        JavaPackage emptyPkg = new JavaPackage("");
        assertEquals("", emptyPkg.getPackageName());
        assertEquals("<root>", emptyPkg.getSimpleName());
    }

    @Test
    void testAddClass() {
        JavaClass myClass = new JavaClass("com.example.MyClass");
        testPackage.addClass(myClass);

        assertEquals(1, testPackage.getClassCount());
        assertTrue(testPackage.getClasses().containsKey("com.example.MyClass"));
    }

    @Test
    void testAddClassWrongPackageThrows() {
        JavaClass wrongPkg = new JavaClass("com.other.MyClass");
        assertThrows(IllegalArgumentException.class, () -> testPackage.addClass(wrongPkg));
    }

    @Test
    void testAddSubPackage() {
        JavaPackage subPkg = new JavaPackage("com.example.sub");
        testPackage.addSubPackage(subPkg);

        assertEquals(1, testPackage.getSubPackageCount());
    }

    @Test
    void testAddPackageDependency() {
        testPackage.addPackageDependency("com.dependency");

        assertTrue(testPackage.getPackageDependencies().contains("com.dependency"));
    }

    @Test
    void testMultipleDependencies() {
        testPackage.addPackageDependency("com.dep1");
        testPackage.addPackageDependency("com.dep2");

        assertEquals(2, testPackage.getPackageDependencies().size());
    }

    @Test
    void testEquality() {
        JavaPackage other = new JavaPackage("com.example");
        assertEquals(testPackage, other);
        assertEquals(testPackage.hashCode(), other.hashCode());
    }

    @Test
    void testInequalityDifferentName() {
        JavaPackage other = new JavaPackage("com.different");
        assertNotEquals(testPackage, other);
    }

    @Test
    void testIsEmpty() {
        assertTrue(testPackage.isEmpty());

        JavaClass myClass = new JavaClass("com.example.MyClass");
        testPackage.addClass(myClass);

        assertFalse(testPackage.isEmpty());
    }
}
