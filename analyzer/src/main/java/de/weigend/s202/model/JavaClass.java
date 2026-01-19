package de.weigend.s202.model;

import java.util.*;

/**
 * Represents a Java class with its metadata and dependencies.
 */
public class JavaClass {
    private final String className;      // Full qualified name: com.example.MyClass
    private final String packageName;    // Package: com.example
    private final String simpleName;     // Simple name: MyClass
    private final Set<ClassDependency> dependencies;

    public JavaClass(String className) {
        this.className = Objects.requireNonNull(className, "className cannot be null");
        this.simpleName = extractSimpleName(className);
        this.packageName = extractPackageName(className);
        this.dependencies = new HashSet<>();
    }

    public String getClassName() {
        return className;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public Set<ClassDependency> getDependencies() {
        return new HashSet<>(dependencies);
    }

    public void addDependency(ClassDependency dependency) {
        Objects.requireNonNull(dependency, "dependency cannot be null");
        dependencies.add(dependency);
    }

    public void addDependencies(Collection<ClassDependency> deps) {
        Objects.requireNonNull(deps, "dependencies cannot be null");
        dependencies.addAll(deps);
    }

    public int getDependencyCount() {
        return dependencies.size();
    }

    private static String extractSimpleName(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) return className;
        return className.substring(lastDot + 1);
    }

    private static String extractPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) return "";
        return className.substring(0, lastDot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JavaClass javaClass = (JavaClass) o;
        return className.equals(javaClass.className);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className);
    }

    @Override
    public String toString() {
        return className;
    }
}
