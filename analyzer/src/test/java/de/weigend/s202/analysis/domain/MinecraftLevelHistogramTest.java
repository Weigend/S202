package de.weigend.s202.analysis.domain;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.PrintWriter;
import java.net.URL;
import java.util.*;

/**
 * Outputs a level histogram, a sorted level-CSV, and a raw-dependency-CSV
 * for comparison with the equivalent C# test (MinecraftLevelHistogramTest.cs).
 *
 * Level CSV:  /tmp/histogram_java_classes.csv  — "className;level"
 * Dep CSV:    /tmp/histogram_java_deps.csv     — "className;depCount;dep1|dep2|..."
 */
public class MinecraftLevelHistogramTest {

    private static final String JAR    = "forge-1.19.2-43.3.0_mapped_official_1.19.2.jar";
    private static final String CSV_LVL = "/tmp/histogram_java_classes.csv";
    private static final String CSV_DEP = "/tmp/histogram_java_deps.csv";

    @Test
    public void printMinecraftLevelHistogram() throws Exception {
        URL jarUrl = getClass().getResource("/" + JAR);
        if (jarUrl == null) {
            System.out.println("SKIPPED: " + JAR + " not found in test resources");
            return;
        }
        String jarPath = new File(jarUrl.getFile()).getAbsolutePath();

        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);

        LevelCalculator calc = new LevelCalculator();
        DomainModel model = calc.calculate(rawModel);

        Map<String, CalculatedElementInfo> classes = model.getAllClasses();
        int maxLevel = model.getMaxLevel();
        int total    = classes.size();

        // ── histogram ──────────────────────────────────────────────────────────
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        for (CalculatedElementInfo info : classes.values())
            hist.merge(info.architectureLevel, 1, Integer::sum);

        System.out.println();
        System.out.println("=== Java Level Histogram: " + JAR + " ===");
        System.out.printf("  Classes: %d   MaxLevel: %d%n", total, maxLevel);
        System.out.println();
        System.out.printf("  %-6s  %6s  %6s  %s%n", "Level", "Count", "%", "Bar");
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
            int level = e.getKey();
            int count = e.getValue();
            double pct = 100.0 * count / total;
            System.out.printf("  L%-5d  %6d  %5.1f%%  %s%n",
                    level, count, pct, "#".repeat(Math.max(1, (int)(pct / 2))));
        }

        // ── level CSV ──────────────────────────────────────────────────────────
        try (PrintWriter pw = new PrintWriter(CSV_LVL)) {
            pw.println("className;level");
            new TreeMap<>(classes).forEach((name, info) ->
                    pw.println(name + ";" + info.architectureLevel));
        }
        System.out.println("\nLevel CSV: " + CSV_LVL);

        // ── dependency CSV (raw model) ──────────────────────────────────────────
        // Only classes that the level-calc also knows about (same universe).
        Set<String> knownClasses = classes.keySet();
        int totalDeps = 0;
        // Structural deps only (non-USES) — what LevelCalculator actually uses
        try (PrintWriter pw = new PrintWriter(CSV_DEP)) {
            pw.println("className;depCount;deps");
            for (String name : new TreeSet<>(knownClasses)) {
                DependencyModel.ClassInfo ci = rawModel.getClass(name);
                List<String> deps = new ArrayList<>();
                if (ci != null) {
                    for (String d : ci.dependencies) {
                        if (!knownClasses.contains(d)) continue;
                        // structural = at least one kind that is not USES
                        java.util.Set<de.weigend.s202.reader.EdgeKind> kinds = ci.getKinds(d);
                        boolean structural = kinds.stream().anyMatch(k -> k != de.weigend.s202.reader.EdgeKind.USES);
                        if (structural) deps.add(d);
                    }
                }
                Collections.sort(deps);
                totalDeps += deps.size();
                pw.println(name + ";" + deps.size() + ";" + String.join("|", deps));
            }
        }
        System.out.println("Dep  CSV: " + CSV_DEP + " (structural only)");
        System.out.printf("Total structural edges: %d  (avg %.1f per class)%n",
                totalDeps, (double) totalDeps / total);
    }
}
