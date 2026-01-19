package de.weigend.s202.analysis;

import de.weigend.s202.model.*;

import java.util.*;

/**
 * Builds a dependency graph from analyzed classes and detects cycles.
 */
public class DependencyGraphBuilder {
    private final Map<String, JavaClass> allClasses;
    private final Map<String, JavaPackage> packageHierarchy;

    public DependencyGraphBuilder() {
        this.allClasses = new HashMap<>();
        this.packageHierarchy = new HashMap<>();
    }

    /**
     * Adds an analyzed class to the graph.
     */
    public void addClass(JavaClass javaClass) {
        Objects.requireNonNull(javaClass, "javaClass cannot be null");
        allClasses.put(javaClass.getClassName(), javaClass);
    }

    /**
     * Builds the complete package hierarchy from analyzed classes.
     */
    public JavaPackage buildPackageHierarchy(String rootPackage) {
        Objects.requireNonNull(rootPackage, "rootPackage cannot be null");
        
        // First pass: create all packages
        for (String className : allClasses.keySet()) {
            String pkg = extractPackageFromClass(className);
            if (pkg.startsWith(rootPackage)) {
                ensurePackageExists(pkg);
            }
        }

        // Second pass: add classes to packages
        for (JavaClass javaClass : allClasses.values()) {
            String pkg = javaClass.getPackageName();
            if (pkg.startsWith(rootPackage)) {
                JavaPackage javaPackage = packageHierarchy.get(pkg);
                if (javaPackage != null) {
                    javaPackage.addClass(javaClass);
                }
            }
        }

        // Third pass: aggregate package dependencies
        aggregatePackageDependencies();

        return packageHierarchy.getOrDefault(rootPackage, new JavaPackage(rootPackage));
    }

    /**
     * Detects cyclic dependencies at package level.
     */
    public List<CyclicDependency> detectCycles(JavaPackage rootPackage) {
        Map<String, Set<String>> dependencyGraph = buildPackageDependencyGraph(rootPackage);
        List<CyclicDependency> cycles = new ArrayList<>();

        for (String pkg : dependencyGraph.keySet()) {
            List<String> cycle = findCycle(pkg, dependencyGraph, new HashSet<>(), new ArrayList<>());
            if (cycle != null && !isDuplicateCycle(cycle, cycles)) {
                int depCount = countCycleDependencies(cycle);
                cycles.add(new CyclicDependency(cycle, depCount));
            }
        }

        return cycles;
    }

    /**
     * Finds a cycle starting from a package.
     */
    private List<String> findCycle(String current, Map<String, Set<String>> graph,
                                   Set<String> visiting, List<String> path) {
        if (visiting.contains(current)) {
            // Found a cycle
            int startIdx = path.indexOf(current);
            if (startIdx >= 0) {
                return new ArrayList<>(path.subList(startIdx, path.size()));
            }
            return null;
        }

        if (path.contains(current)) {
            return null;
        }

        visiting.add(current);
        path.add(current);

        Set<String> neighbors = graph.getOrDefault(current, new HashSet<>());
        for (String neighbor : neighbors) {
            List<String> result = findCycle(neighbor, graph, new HashSet<>(visiting), new ArrayList<>(path));
            if (result != null && !result.isEmpty()) {
                return result;
            }
        }

        return null;
    }

    /**
     * Builds a package-level dependency graph.
     */
    private Map<String, Set<String>> buildPackageDependencyGraph(JavaPackage rootPackage) {
        Map<String, Set<String>> graph = new HashMap<>();
        collectPackageDependencies(rootPackage, graph);
        return graph;
    }

    private void collectPackageDependencies(JavaPackage pkg, Map<String, Set<String>> graph) {
        graph.putIfAbsent(pkg.getPackageName(), new HashSet<>(pkg.getPackageDependencies()));

        for (JavaPackage subPkg : pkg.getSubPackages().values()) {
            collectPackageDependencies(subPkg, graph);
        }
    }

    /**
     * Counts dependencies in a cycle.
     */
    private int countCycleDependencies(List<String> cycle) {
        int count = 0;
        for (int i = 0; i < cycle.size(); i++) {
            String from = cycle.get(i);
            String to = cycle.get((i + 1) % cycle.size());
            
            for (JavaClass cls : allClasses.values()) {
                if (cls.getPackageName().equals(from)) {
                    for (ClassDependency dep : cls.getDependencies()) {
                        if (dep.getTargetClass().startsWith(to + ".")) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Checks if a cycle is already in the list.
     */
    private boolean isDuplicateCycle(List<String> cycle, List<CyclicDependency> cycles) {
        Set<String> cycleSet = new HashSet<>(cycle);
        for (CyclicDependency existing : cycles) {
            if (new HashSet<>(existing.getCycle()).equals(cycleSet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aggregates class-level dependencies to package level.
     */
    private void aggregatePackageDependencies() {
        Set<String> processedPairs = new HashSet<>();

        for (JavaClass javaClass : allClasses.values()) {
            String sourcePackage = javaClass.getPackageName();
            JavaPackage sourcePkg = packageHierarchy.get(sourcePackage);

            if (sourcePkg == null) continue;

            for (ClassDependency classDep : javaClass.getDependencies()) {
                String targetClass = classDep.getTargetClass();
                String targetPackage = extractPackageFromClass(targetClass);

                // Avoid self-dependencies and duplicates
                String pairKey = sourcePackage + "->" + targetPackage;
                if (!sourcePackage.equals(targetPackage) && !processedPairs.contains(pairKey)) {
                    sourcePkg.addPackageDependency(targetPackage);
                    processedPairs.add(pairKey);
                }
            }
        }
    }

    /**
     * Ensures all packages in the hierarchy exist.
     */
    private void ensurePackageExists(String packageName) {
        if (packageHierarchy.containsKey(packageName)) {
            return;
        }

        // Create the package
        JavaPackage pkg = new JavaPackage(packageName);
        packageHierarchy.put(packageName, pkg);

        // Ensure parent exists
        int lastDot = packageName.lastIndexOf('.');
        if (lastDot > 0) {
            String parentPackage = packageName.substring(0, lastDot);
            ensurePackageExists(parentPackage);
            JavaPackage parentPkg = packageHierarchy.get(parentPackage);
            if (parentPkg != null) {
                parentPkg.addSubPackage(pkg);
            }
        }
    }

    private String extractPackageFromClass(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }

    public Map<String, JavaClass> getAllClasses() {
        return new HashMap<>(allClasses);
    }
}
