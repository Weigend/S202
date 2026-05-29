package de.weigend.s202.analysis.domain;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculationStrategyFactory;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.InputAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class MinecraftPackageLevelTest {

    private static final String JAR = "forge-1.19.2-43.3.0_mapped_official_1.19.2.jar";

    @Test
    public void dumpMinecraftPackageLevels() throws Exception {
        URL jarUrl = getClass().getResource("/" + JAR);
        if (jarUrl == null) { System.out.println("SKIPPED"); return; }

        var rawModel = new InputAnalyzer().analyze(new File(jarUrl.getFile()).getAbsolutePath());
        var model    = new LevelCalculator(LevelCalculationStrategyFactory.createDefault()).calculate(rawModel);

        var packages = model.getAllPackages().entrySet().stream()
            .filter(e -> e.getKey().startsWith("net.minecraft.") || e.getKey().startsWith("com.mojang."))
            .sorted(Comparator.comparingInt((Map.Entry<String, DomainModel.CalculatedElementInfo> e)
                -> e.getValue().architectureLevel).thenComparing(Map.Entry::getKey))
            .collect(Collectors.toList());

        try (PrintWriter pw = new PrintWriter("/tmp/pkg_levels_java.csv")) {
            pw.println("package;level");
            for (var e : packages) pw.println(e.getKey() + ";" + e.getValue().architectureLevel);
        }

        // histogram
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        for (var e : packages) hist.merge(e.getValue().architectureLevel, 1, Integer::sum);
        int total = hist.values().stream().mapToInt(i -> i).sum();
        int maxLvl = hist.lastKey();

        System.out.printf("%n=== Java Package Level Distribution (net.minecraft.* + com.mojang.*) ===%n");
        System.out.printf("  Packages: %d   MaxLevel: %d%n", total, maxLvl);
        System.out.printf("  %-6s  %5s  %5s  %s%n", "Level", "Count", "%", "Bar");
        for (var e : hist.entrySet()) {
            double pct = 100.0 * e.getValue() / total;
            System.out.printf("  L%-5d  %5d  %4.1f%%  %s%n",
                e.getKey(), e.getValue(), pct, "#".repeat((int)(pct * 2)));
        }
        System.out.println("\nCSV: /tmp/pkg_levels_java.csv");
    }
}
