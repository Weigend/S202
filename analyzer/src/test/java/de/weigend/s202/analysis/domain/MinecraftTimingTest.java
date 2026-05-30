package de.weigend.s202.analysis.domain;

import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

public class MinecraftTimingTest {

    private static final String JAR = "forge-1.19.2-43.3.0_mapped_official_1.19.2.jar";

    @Test
    public void measureMinecraftPipelineTiming() throws Exception {
        URL jarUrl = getClass().getResource("/" + JAR);
        if (jarUrl == null) { System.out.println("SKIPPED: " + JAR + " not found"); return; }
        String jarPath = new File(jarUrl.getFile()).getAbsolutePath();

        // warm up
        new InputAnalyzer().analyze(jarPath);

        long t0 = System.currentTimeMillis();
        DependencyModel rawModel = new InputAnalyzer().analyze(jarPath);
        long parseMs = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        var model = new LevelCalculator().calculate(rawModel);
        long calcMs = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("=== Java Pipeline Timing: " + JAR + " ===");
        System.out.printf("  Parse (analyze):           %6d ms%n", parseMs);
        System.out.printf("  LevelCalculator.calculate: %6d ms%n", calcMs);
        System.out.printf("  Total:                     %6d ms%n", parseMs + calcMs);
        System.out.printf("  Classes: %d   MaxLevel: %d%n",
                model.getAllClasses().size(), model.getMaxLevel());
    }
}
