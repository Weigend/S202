package de.weigend.s202.model;

import java.util.Objects;

/**
 * Represents a dependency from one class to another class.
 */
public class ClassDependency {
    private final String sourceClass;
    private final String targetClass;
    private final DependencyType type;

    public enum DependencyType {
        DIRECT,      // Direct reference
        INHERITANCE, // Class extends/implements
        ANNOTATION,  // Used in annotation
        GENERIC      // Used in generic type
    }

    public ClassDependency(String sourceClass, String targetClass, DependencyType type) {
        this.sourceClass = Objects.requireNonNull(sourceClass, "sourceClass cannot be null");
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    public String getSourceClass() {
        return sourceClass;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public DependencyType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClassDependency that = (ClassDependency) o;
        return sourceClass.equals(that.sourceClass) &&
               targetClass.equals(that.targetClass) &&
               type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceClass, targetClass, type);
    }

    @Override
    public String toString() {
        return sourceClass + " -> " + targetClass + " (" + type + ")";
    }
}
