package de.weigend.s202.analysis.invariants;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.analysis.strategy.LevelCalculationStrategyFactory;
import de.weigend.s202.analysis.strategy.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.InputAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MinecraftInvariantCheckTest {

    @Test
    public void minecraftPipelinePassesAllInvariants() throws Exception {
        URL jarUrl = getClass().getResource("/forge-1.12.2-14.23.5.2859_mapped_snapshot_20171003-1.12.jar");
        if (jarUrl == null) { System.out.println("SKIPPED: JAR not found"); return; }

        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(new File(jarUrl.getFile()).getAbsolutePath());

        LevelCalculator calc = new LevelCalculator(LevelCalculationStrategyFactory.createWithHeuristicSCCBreaking());
        DomainModel model = calc.calculate(rawModel);

        LayoutInvariantChecker checker = new LayoutInvariantChecker();
        LayoutInvariantReport report = checker.check(model, rawModel, List.of(jarUrl.getFile()));

        for (InvariantFinding f : report.findings()) {
            System.out.println("[" + f.ruleId() + "] " + f.message());
            System.out.println("  from: " + f.fromName() + " (L" + f.fromLevel() + ")");
            System.out.println("    to: " + f.toName()   + " (L" + f.toLevel()   + ")");
        }

        assertEquals(0, report.findings().size(),
            "Minecraft 1.12 pipeline must pass all invariants — " + report.toReproducerText());
    }
}
