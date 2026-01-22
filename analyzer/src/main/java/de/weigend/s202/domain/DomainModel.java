package de.weigend.s202.domain;

import java.util.*;

/**
 * Model containing calculated level information for classes and packages.
 */
public class DomainModel {
    private final Map<String, CalculatedElementInfo> classes = new HashMap<>();
    private final Map<String, CalculatedElementInfo> packages = new HashMap<>();

    /**
     * Information about a calculated element (class or package) with its level.
     */
    public static class CalculatedElementInfo {
        public final String fullName;
        public final String simpleName;
        public final String type; // "CLASS" or "PACKAGE"
        public int level;
        public final Set<String> dependencies;
        public final Set<String> dependents = new HashSet<>();

        public CalculatedElementInfo(String fullName, String simpleName, String type, int level, Set<String> dependencies) {
            this.fullName = fullName;
            this.simpleName = simpleName;
            this.type = type;
            this.level = level;
            this.dependencies = dependencies;
        }

        public void setLevel(int newLevel) {
            this.level = newLevel;
        }

        public void addDependency(String dependency) {
            this.dependencies.add(dependency);
        }

        public void addDependent(String dependent) {
            this.dependents.add(dependent);
        }
    }

    // ===== Public API =====

    public void addClass(String className, CalculatedElementInfo classInfo) {
        classes.put(className, classInfo);
    }

    public CalculatedElementInfo getClass(String className) {
        return classes.get(className);
    }

    public Map<String, CalculatedElementInfo> getAllClasses() {
        return new HashMap<>(classes);
    }

    public void addPackage(String packageName, CalculatedElementInfo pkgInfo) {
        packages.put(packageName, pkgInfo);
    }

    public CalculatedElementInfo getPackage(String packageName) {
        return packages.get(packageName);
    }

    public Map<String, CalculatedElementInfo> getAllPackages() {
        return new HashMap<>(packages);
    }

    /**
     * Gets all elements (classes and packages) grouped by level.
     */
    public Map<Integer, List<CalculatedElementInfo>> getElementsByLevel() {
        Map<Integer, List<CalculatedElementInfo>> result = new TreeMap<>();

        for (CalculatedElementInfo classInfo : classes.values()) {
            result.computeIfAbsent(classInfo.level, k -> new ArrayList<>()).add(classInfo);
        }

        for (CalculatedElementInfo pkgInfo : packages.values()) {
            result.computeIfAbsent(pkgInfo.level, k -> new ArrayList<>()).add(pkgInfo);
        }

        return result;
    }

    public int getMaxLevel() {
        int max = 0;
        for (CalculatedElementInfo classInfo : classes.values()) {
            max = Math.max(max, classInfo.level);
        }
        for (CalculatedElementInfo pkgInfo : packages.values()) {
            max = Math.max(max, pkgInfo.level);
        }
        return max;
    }
}
