package de.weigend.s202.analysis.domain;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dumps the structural dependency graph (after analysis) to a neutral CSV
 * that both Java and .NET can read to compute levels on identical input.
 *
 * Format per line:  className;dep1|dep2|...
 *
 * Output:
 *   /tmp/mc_deps.csv          — structural deps (input for level calc)
 *   /tmp/mc_levels_java.csv   — className;level computed by Java
 */
public class MinecraftDepDumpTest {

    private static final String JAR     = "forge-1.19.2-43.3.0_mapped_official_1.19.2.jar";
    public  static final String DEPS    = "/tmp/mc_deps.csv";
    public  static final String LEVELS  = "/tmp/mc_levels_java.csv";

    @Test
    public void dumpDepsAndLevels() throws Exception {
        URL jarUrl = getClass().getResource("/" + JAR);
        if (jarUrl == null) { System.out.println("SKIPPED"); return; }
        String jarPath = new File(jarUrl.getFile()).getAbsolutePath();

        DependencyModel rawModel = new InputAnalyzer().analyze(jarPath);
        DomainModel model = new LevelCalculator().calculate(rawModel);

        Set<String> knownClasses = model.getAllClasses().keySet();

        // ── dump structural deps + package levels in one CSV ────────────────
        // Format:
        //   CLASS className;dep1|dep2|...
        //   PKG   packageName;level
        try (PrintWriter pw = new PrintWriter(DEPS)) {
            // class structural deps
            for (String name : new TreeSet<>(knownClasses)) {
                DependencyModel.ClassInfo ci = rawModel.getClass(name);
                List<String> deps = new ArrayList<>();
                if (ci != null) {
                    for (String d : ci.dependencies) {
                        if (!knownClasses.contains(d)) continue;
                        Set<EdgeKind> kinds = ci.getKinds(d);
                        if (kinds.stream().anyMatch(k -> k != EdgeKind.USES)) deps.add(d);
                    }
                }
                Collections.sort(deps);
                pw.println("CLASS " + name + ";" + String.join("|", deps));
            }
            // method call counts: MCALLS className;toClass:count|...
            for (String name : new TreeSet<>(knownClasses)) {
                DependencyModel.ClassInfo ci = rawModel.getClass(name);
                if (ci == null || ci.methods.isEmpty()) continue;
                // aggregate call counts per target class
                Map<String, Integer> callsTo = new TreeMap<>();
                for (DependencyModel.MethodInfo method : ci.methods.values()) {
                    for (Map.Entry<String, Integer> call : method.methodCalls.entrySet()) {
                        String target = call.getKey();
                        int dot = target.lastIndexOf('.');
                        if (dot < 0) continue;
                        String targetClass = target.substring(0, dot);
                        if (!knownClasses.contains(targetClass)) continue;
                        callsTo.merge(targetClass, call.getValue(), Integer::sum);
                    }
                }
                if (callsTo.isEmpty()) continue;
                List<String> parts = new ArrayList<>();
                callsTo.forEach((t, c) -> parts.add(t + ":" + c));
                pw.println("MCALLS " + name + ";" + String.join("|", parts));
            }
            // package levels (pre-computed by Java, used as hypothesis input)
            for (String pkg : new TreeSet<>(model.getAllPackages().keySet())) {
                int lvl = model.getAllPackages().get(pkg).architectureLevel;
                pw.println("PKG " + pkg + ";" + lvl);
            }
        }

        // ── dump all levels computed by Java ────────────────────────────────
        // Format: name;archLevel;localLevel;type(CLASS|PKG)
        try (PrintWriter pw = new PrintWriter(LEVELS)) {
            pw.println("name;archLevel;localLevel;type");
            new TreeMap<>(model.getAllClasses()).forEach((name, info) ->
                pw.println(name + ";" + info.architectureLevel + ";" + info.localLevel + ";CLASS"));
            new TreeMap<>(model.getAllPackages()).forEach((name, info) ->
                pw.println(name + ";" + info.architectureLevel + ";" + info.localLevel + ";PKG"));
        }

        int total  = knownClasses.size();
        int maxLvl = model.getAllClasses().values().stream()
            .mapToInt(i -> i.architectureLevel).max().orElse(0);
        System.out.printf("Dumped %d classes, MaxLevel %d%n  deps+pkgLevels: %s%n  levels: %s%n",
            total, maxLvl, DEPS, LEVELS);
    }
}
