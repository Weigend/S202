package de.weigend.s202.model;

import java.util.*;

/**
 * Represents a Java package hierarchy with classes and package dependencies.
 */
public class JavaPackage {
    private final String packageName;
    private final Map<String, JavaClass> classes;
    private final Map<String, JavaPackage> subPackages;
    private final Set<String> packageDependencies;  // Simplified package deps

    public JavaPackage(String packageName) {
        this.packageName = Objects.requireNonNull(packageName, "packageName cannot be null");
        this.classes = new HashMap<>();
        this.subPackages = new HashMap<>();
        this.packageDependencies = new HashSet<>();
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSimpleName() {
        if (packageName.isEmpty()) return "<root>";
        String[] parts = packageName.split("\\.");
        return parts[parts.length - 1];
    }

    public void addClass(JavaClass javaClass) {
        Objects.requireNonNull(javaClass, "javaClass cannot be null");
        if (!javaClass.getPackageName().equals(packageName)) {
            throw new IllegalArgumentException(
                "Class " + javaClass.getClassName() + " does not belong to package " + packageName);
        }
        classes.put(javaClass.getClassName(), javaClass);
    }

    public void addSubPackage(JavaPackage subPackage) {
        Objects.requireNonNull(subPackage, "subPackage cannot be null");
        String expectedPrefix = packageName.isEmpty() ? "" : packageName + ".";
        if (!subPackage.packageName.startsWith(expectedPrefix + subPackage.getSimpleName())) {
            // Allow direct children
            if (!subPackage.packageName.substring(0, Math.min(subPackage.packageName.length(), expectedPrefix.length() + 20))
                    .startsWith(packageName.isEmpty() ? "" : packageName)) {
                throw new IllegalArgumentException("Invalid sub-package hierarchy");
            }
        }
        subPackages.put(subPackage.getPackageName(), subPackage);
    }

    public void addPackageDependency(String dependencyPackage) {
        Objects.requireNonNull(dependencyPackage, "dependencyPackage cannot be null");
        packageDependencies.add(dependencyPackage);
    }

    public Map<String, JavaClass> getClasses() {
        return new HashMap<>(classes);
    }

    public Map<String, JavaPackage> getSubPackages() {
        return new HashMap<>(subPackages);
    }

    public Set<String> getPackageDependencies() {
        return new HashSet<>(packageDependencies);
    }

    public int getClassCount() {
        return classes.size();
    }

    public int getSubPackageCount() {
        return subPackages.size();
    }

    public boolean isEmpty() {
        return classes.isEmpty() && subPackages.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JavaPackage that = (JavaPackage) o;
        return packageName.equals(that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName);
    }

    @Override
    public String toString() {
        return packageName.isEmpty() ? "<root>" : packageName;
    }
}
