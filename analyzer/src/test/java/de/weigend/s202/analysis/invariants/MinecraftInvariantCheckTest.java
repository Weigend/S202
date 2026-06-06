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
package de.weigend.s202.analysis.invariants;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.impl.java.InputAnalyzer;
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

        LevelCalculator calc = new LevelCalculator();
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
