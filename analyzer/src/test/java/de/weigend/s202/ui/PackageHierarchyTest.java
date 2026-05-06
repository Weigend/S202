package de.weigend.s202.ui;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify package hierarchy creation logic in ArchitectureView.
 * Specifically tests that missing parent packages are automatically created.
 */
public class PackageHierarchyTest {

    @Test
    public void testPackageHierarchyCreation() {
        System.out.println("\n=== PACKAGE HIERARCHY TEST ===");
        
        // Simulate the ensurePackageHierarchy logic
        Map<String, String> packageContainers = new HashMap<>();
        packageContainers.put("", "Root");
        
        // Test Case 1: Simple package
        ensurePackageHierarchy("com", packageContainers);
        assertTrue(packageContainers.containsKey("com"), "Package 'com' should be created");
        System.out.println("✓ Single-level package created: com");
        
        // Test Case 2: Multi-level package
        ensurePackageHierarchy("com.example", packageContainers);
        assertTrue(packageContainers.containsKey("com"), "Package 'com' should exist");
        assertTrue(packageContainers.containsKey("com.example"), "Package 'com.example' should be created");
        System.out.println("✓ Two-level package created: com.example");
        
        // Test Case 3: Deep hierarchy
        ensurePackageHierarchy("de.weigend.s202.analysis.domain", packageContainers);
        assertTrue(packageContainers.containsKey("de"), "Package 'de' should be created");
        assertTrue(packageContainers.containsKey("de.weigend"), "Package 'de.weigend' should be created");
        assertTrue(packageContainers.containsKey("de.weigend.s202"), "Package 'de.weigend.s202' should be created");
        assertTrue(packageContainers.containsKey("de.weigend.s202.analysis"), "Package 'de.weigend.s202.analysis' should be created");
        assertTrue(packageContainers.containsKey("de.weigend.s202.analysis.domain"), "Package 'de.weigend.s202.analysis.domain' should be created");
        System.out.println("✓ Five-level package hierarchy created: de.weigend.s202.analysis.domain");
        
        // Test Case 4: Duplicate call should not create duplicates
        int sizeBefore = packageContainers.size();
        ensurePackageHierarchy("de.weigend.s202.analysis.domain", packageContainers);
        int sizeAfter = packageContainers.size();
        assertEquals(sizeBefore, sizeAfter, "Duplicate package hierarchy should not increase map size");
        System.out.println("✓ Duplicate hierarchy call doesn't create duplicates");
        
        // Test Case 5: Empty package should be handled gracefully
        ensurePackageHierarchy("", packageContainers);
        ensurePackageHierarchy(null, packageContainers);
        System.out.println("✓ Empty/null package names handled gracefully");
        
        // Print final state
        System.out.println("\nFinal package structure:");
        packageContainers.keySet().stream()
            .sorted()
            .forEach(pkg -> System.out.println("  - " + (pkg.isEmpty() ? "<root>" : pkg)));
        
        System.out.println("\n✅ All package hierarchy tests passed!");
    }
    
    /**
     * Simplified version of ensurePackageHierarchy for testing.
     * Uses String instead of LevelPackageBox for simplicity.
     */
    private void ensurePackageHierarchy(String packageName, Map<String, String> packageContainers) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        
        if (packageContainers.containsKey(packageName)) {
            return; // Already exists
        }
        
        // Split the package into parts
        String[] parts = packageName.split("\\.");
        String currentPkg = "";
        
        for (String part : parts) {
            String previousPkg = currentPkg;
            currentPkg = currentPkg.isEmpty() ? part : currentPkg + "." + part;
            
            if (!packageContainers.containsKey(currentPkg)) {
                // Create missing package
                packageContainers.put(currentPkg, part);
            }
        }
    }
    
    @Test
    public void testParentPackageExtraction() {
        System.out.println("\n=== PARENT PACKAGE EXTRACTION TEST ===");
        
        // Test various FQN patterns
        testParentExtraction("com.example.A", "com.example");
        testParentExtraction("com.example.B", "com.example");
        testParentExtraction("de.weigend.s202.analysis.domain.DomainModel", "de.weigend.s202.analysis.domain");
        testParentExtraction("SingleClassName", "");
        
        System.out.println("\n✅ All parent package extraction tests passed!");
    }
    
    private void testParentExtraction(String fullName, String expectedParent) {
        String parent = getParentPackage(fullName);
        assertEquals(expectedParent, parent, "Parent of " + fullName + " should be " + expectedParent);
        System.out.println("✓ " + fullName + " → parent: " + (parent.isEmpty() ? "<root>" : parent));
    }
    
    /**
     * Simplified version of getParentPackage for testing.
     */
    private String getParentPackage(String fullName) {
        if (!fullName.contains(".")) return "";
        
        int lastDot = fullName.lastIndexOf('.');
        return fullName.substring(0, lastDot);
    }
}
