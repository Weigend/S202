package de.weigend.s202.analysis.quality;

import de.weigend.s202.analysis.scc.StronglyConnectedComponent;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Two scalar quality dimensions for an analysed JAR, both normalised to
 * {@code [0, 1]} where 0 is healthy (green) and 1 is unhealthy (red).
 *
 * <ul>
 *   <li><b>fat</b> — average outgoing class dependencies, scaled so 10
 *       deps/class ≈ fully fat. Rough proxy for dependency density.</li>
 *   <li><b>tangled</b> — share of class dependencies that participate in a
 *       cycle (intra-SCC edges where the SCC has size &gt; 1) over all class
 *       dependencies. 0 means cycle-free, 1 means everything is in one big
 *       cycle.</li>
 * </ul>
 */
public final class QualityMetrics {

    /** Avg deps/class at which fat saturates to 1.0. Tunable. */
    private static final double FAT_SATURATION = 10.0;

    private final double fat;
    private final double tangled;
    private final int classCount;
    private final int dependencyCount;
    private final int intraSccDependencyCount;

    private QualityMetrics(double fat, double tangled, int classCount,
                           int dependencyCount, int intraSccDependencyCount) {
        this.fat = fat;
        this.tangled = tangled;
        this.classCount = classCount;
        this.dependencyCount = dependencyCount;
        this.intraSccDependencyCount = intraSccDependencyCount;
    }

    public double getFat() { return fat; }
    public double getTangled() { return tangled; }
    public int getClassCount() { return classCount; }
    public int getDependencyCount() { return dependencyCount; }
    public int getIntraSccDependencyCount() { return intraSccDependencyCount; }

    public static QualityMetrics compute(DomainModel model) {
        return computeForScope(model, null);
    }

    /**
     * Compute metrics restricted to classes whose full name starts with
     * {@code packageFullName + "."} (or equals it — same package). Pass
     * {@code null} for the whole JAR.
     */
    public static QualityMetrics computeForPackage(DomainModel model, String packageFullName) {
        if (packageFullName == null || packageFullName.isEmpty()) {
            return compute(model);
        }
        String prefix = packageFullName + ".";
        return computeForScope(model, name -> name.equals(packageFullName) || name.startsWith(prefix));
    }

    private static QualityMetrics computeForScope(DomainModel model,
                                                  java.util.function.Predicate<String> classFilter) {
        if (model == null) {
            return new QualityMetrics(0, 0, 0, 0, 0);
        }
        Map<String, CalculatedElementInfo> allClasses = model.getAllClasses();
        Map<String, CalculatedElementInfo> classes = new HashMap<>();
        for (Map.Entry<String, CalculatedElementInfo> e : allClasses.entrySet()) {
            if (classFilter == null || classFilter.test(e.getKey())) {
                classes.put(e.getKey(), e.getValue());
            }
        }
        int n = classes.size();

        // Build class-only graph and count internal-only deps for fairness.
        Map<String, Set<String>> graph = new HashMap<>(n);
        int totalDeps = 0;
        for (CalculatedElementInfo info : classes.values()) {
            Set<String> internalDeps = new HashSet<>();
            for (String dep : info.dependencies) {
                if (classes.containsKey(dep)) {
                    internalDeps.add(dep);
                }
            }
            graph.put(info.fullName, internalDeps);
            totalDeps += internalDeps.size();
        }

        int intraScc = 0;
        if (totalDeps > 0) {
            for (StronglyConnectedComponent scc : new TarjanSCCFinder(graph).findSCCs()) {
                if (!scc.isTangle()) {
                    continue;
                }
                Set<String> members = scc.getMembers();
                for (String m : members) {
                    Set<String> deps = graph.get(m);
                    if (deps == null) continue;
                    for (String d : deps) {
                        if (members.contains(d)) {
                            intraScc++;
                        }
                    }
                }
            }
        }

        double avgDeps = n == 0 ? 0 : (double) totalDeps / n;
        double fat = Math.min(1.0, avgDeps / FAT_SATURATION);
        double tangled = totalDeps == 0 ? 0 : (double) intraScc / totalDeps;
        return new QualityMetrics(fat, tangled, n, totalDeps, intraScc);
    }

    @Override
    public String toString() {
        return String.format("QualityMetrics{fat=%.3f, tangled=%.3f, classes=%d, deps=%d, intraScc=%d}",
                fat, tangled, classCount, dependencyCount, intraSccDependencyCount);
    }
}
