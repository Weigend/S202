package de.weigend.s202.reader;

import java.util.*;

/**
 * Pure data model containing raw bytecode analysis results.
 * NO layer calculation, NO UI concerns.
 */
public class DependencyModel {
    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Set<String> allClassNames = new HashSet<>();
    private Map<String, PackageInfo> packages = new HashMap<>();

    /**
     * Information about a single Java class.
     */
    public static class ClassInfo {
        public final String fullName;
        public final String simpleName;
        public final String packageName;
        public final boolean interfaceType;
        public final Set<String> dependencies = new HashSet<>();
        /** Kinds per dependency target. Same keys as {@link #dependencies}. */
        public final Map<String, EnumSet<EdgeKind>> dependencyKinds = new HashMap<>();
        public final Map<String, MethodInfo> methods = new HashMap<>();

        public ClassInfo(String fullName, String simpleName, String packageName) {
            this(fullName, simpleName, packageName, false);
        }

        public ClassInfo(String fullName, String simpleName, String packageName, boolean interfaceType) {
            this.fullName = fullName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.interfaceType = interfaceType;
        }

        /**
         * Record a dependency on {@code target} with its relationship kind.
         * Updates both the flat {@link #dependencies} set (used by level
         * calculation, SCC analysis, etc.) and the typed
         * {@link #dependencyKinds} map (used by features that care about
         * "what kind of edge", e.g. the Top Tangles view).
         */
        public void addDependency(String target, EdgeKind kind) {
            dependencies.add(target);
            dependencyKinds.computeIfAbsent(target, k -> EnumSet.noneOf(EdgeKind.class)).add(kind);
        }

        /** Kinds via which this class depends on {@code target}, or empty if no such edge. */
        public Set<EdgeKind> getKinds(String target) {
            EnumSet<EdgeKind> set = dependencyKinds.get(target);
            return set == null ? Set.of() : EnumSet.copyOf(set);
        }

        public void addMethod(String name, String descriptor) {
            String key = name + descriptor;
            methods.putIfAbsent(key, new MethodInfo(name, descriptor));
        }

        public MethodInfo getMethod(String name, String descriptor) {
            return methods.get(name + descriptor);
        }
    }

    /**
     * Information about a method including its calls.
     */
    public static class MethodInfo {
        public final String name;
        public final String descriptor;
        public final Map<String, Integer> methodCalls = new HashMap<>();

        public MethodInfo(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    /**
     * Information about a package and its structure.
     */
    public static class PackageInfo {
        public final String fullName;
        public final String simpleName;
        public final Set<String> childPackages = new HashSet<>();
        public final Set<String> classNames = new HashSet<>();

        public PackageInfo(String fullName, String simpleName) {
            this.fullName = fullName;
            this.simpleName = simpleName;
        }
    }

    // ===== Public API =====

    public void addClass(String className, ClassInfo classInfo) {
        classes.put(className, classInfo);
        allClassNames.add(className);
    }

    public ClassInfo getClass(String className) {
        return classes.get(className);
    }

    public Set<String> getAllClassNames() {
        return new HashSet<>(allClassNames);
    }

    public int getClassCount() {
        return classes.size();
    }

    public void setPackages(Map<String, PackageInfo> packages) {
        this.packages = packages;
    }

    public PackageInfo getPackage(String packageName) {
        return packages.get(packageName);
    }

    public Set<String> getAllPackageNames() {
        return new HashSet<>(packages.keySet());
    }

    public int getPackageCount() {
        return packages.size();
    }

    public Map<String, ClassInfo> getAllClasses() {
        return new HashMap<>(classes);
    }

    public Map<String, PackageInfo> getAllPackages() {
        return new HashMap<>(packages);
    }
}
