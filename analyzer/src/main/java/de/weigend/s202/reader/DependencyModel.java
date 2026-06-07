/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.reader;

import java.util.*;

/**
 * Pure data model containing raw bytecode analysis results.
 * NO layer calculation, NO UI concerns.
 */
public class DependencyModel {
    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Set<String> allClassNames = new HashSet<>();
    private final Map<String, ModuleInfo> modules = new HashMap<>();
    private Map<String, PackageInfo> packages = new HashMap<>();
    private final Set<String> componentAnnotatedPackages = new LinkedHashSet<>();
    private final Set<String> apiAnnotatedPackages = new LinkedHashSet<>();

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
        public final Map<String, Set<String>> methodCallDescriptors = new HashMap<>();

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

    /**
     * Java Platform Module System descriptor information from module-info.class.
     */
    public static class ModuleInfo {
        public final String name;
        public final String version;
        public final Set<ModulePackageAccess> exportedPackages = new HashSet<>();
        public final Set<ModulePackageAccess> openedPackages = new HashSet<>();

        public ModuleInfo(String name, String version) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("module name must be non-empty");
            }
            this.name = name;
            this.version = version;
        }

        public void addExportedPackage(String packageName, Collection<String> targetModules) {
            exportedPackages.add(new ModulePackageAccess(packageName, targetModules));
        }

        public void addOpenedPackage(String packageName, Collection<String> targetModules) {
            openedPackages.add(new ModulePackageAccess(packageName, targetModules));
        }
    }

    /**
     * Package access declared by exports/opens. Empty targetModules means the
     * package is exported/opened unqualified.
     */
    public record ModulePackageAccess(String packageName, Set<String> targetModules) {
        public ModulePackageAccess(String packageName, Collection<String> targetModules) {
            this(packageName, targetModules == null ? Set.of() : Set.copyOf(targetModules));
        }

        public ModulePackageAccess {
            if (packageName == null || packageName.isBlank()) {
                throw new IllegalArgumentException("packageName must be non-empty");
            }
            targetModules = targetModules == null ? Set.of() : Set.copyOf(targetModules);
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

    public ModuleInfo getOrCreateModule(String moduleName, String version) {
        return modules.computeIfAbsent(moduleName, name -> new ModuleInfo(name, version));
    }

    public void addModule(ModuleInfo moduleInfo) {
        if (moduleInfo == null) {
            return;
        }
        ModuleInfo existing = getOrCreateModule(moduleInfo.name, moduleInfo.version);
        existing.exportedPackages.addAll(moduleInfo.exportedPackages);
        existing.openedPackages.addAll(moduleInfo.openedPackages);
    }

    public ModuleInfo getModule(String moduleName) {
        return modules.get(moduleName);
    }

    public int getModuleCount() {
        return modules.size();
    }

    public Map<String, ModuleInfo> getAllModules() {
        return new HashMap<>(modules);
    }

    public Set<String> getExportedPackageNames() {
        Set<String> exported = new HashSet<>();
        for (ModuleInfo module : modules.values()) {
            for (ModulePackageAccess access : module.exportedPackages) {
                exported.add(access.packageName());
            }
        }
        return exported;
    }

    public boolean isPackageExported(String packageName) {
        return packageName != null && getExportedPackageNames().contains(packageName);
    }

    public void addComponentAnnotatedPackage(String packageFqn) {
        if (packageFqn != null && !packageFqn.isBlank()) {
            componentAnnotatedPackages.add(packageFqn);
        }
    }

    public Set<String> getComponentAnnotatedPackages() {
        return Collections.unmodifiableSet(componentAnnotatedPackages);
    }

    public void addApiAnnotatedPackage(String packageFqn) {
        if (packageFqn != null && !packageFqn.isBlank()) {
            apiAnnotatedPackages.add(packageFqn);
        }
    }

    public Set<String> getApiAnnotatedPackages() {
        return Collections.unmodifiableSet(apiAnnotatedPackages);
    }

    public Map<String, ClassInfo> getAllClasses() {
        return new HashMap<>(classes);
    }

    public Map<String, PackageInfo> getAllPackages() {
        return new HashMap<>(packages);
    }
}
