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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a layout-invariant check, with a copy-paste-friendly reproducer
 * rendering for handing to a developer or LLM.
 */
public final class LayoutInvariantReport {

    private static final DateTimeFormatter UTC = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** Cap per-rule findings in the rendered report; the rest is summarised. */
    private static final int MAX_FINDINGS_PER_RULE = 50;

    private final List<String> sourcePaths;
    private final int maxLevel;
    private final int packageCount;
    private final int classCount;
    private final int dependencyCount;
    private final int identifiedBackEdgeCount;
    private final List<InvariantFinding> findings;

    public LayoutInvariantReport(List<String> sourcePaths,
                                 int maxLevel,
                                 int packageCount,
                                 int classCount,
                                 int dependencyCount,
                                 int identifiedBackEdgeCount,
                                 List<InvariantFinding> findings) {
        this.sourcePaths = List.copyOf(sourcePaths);
        this.maxLevel = maxLevel;
        this.packageCount = packageCount;
        this.classCount = classCount;
        this.dependencyCount = dependencyCount;
        this.identifiedBackEdgeCount = identifiedBackEdgeCount;
        this.findings = List.copyOf(findings);
    }

    public List<String> sourcePaths()      { return sourcePaths; }
    public int maxLevel()                  { return maxLevel; }
    public int packageCount()              { return packageCount; }
    public int classCount()               { return classCount; }
    public int dependencyCount()           { return dependencyCount; }
    public int identifiedBackEdgeCount()   { return identifiedBackEdgeCount; }
    public List<InvariantFinding> findings(){ return findings; }
    public boolean hasFindings()           { return !findings.isEmpty(); }

    /**
     * Multiline, copy-paste-friendly report suitable as input to a reproducer
     * test or as a prompt for an assistant. Includes the source inputs (jar
     * paths), graph dimensions, and one block per finding.
     */
    public String toReproducerText() {
        StringBuilder sb = new StringBuilder(2_048);
        sb.append("=== Structure202 Layout Invariant Report ===\n");
        sb.append("Generated: ").append(UTC.format(Instant.now())).append('\n');
        sb.append("Scope: algorithm-bug detector (R1 non-back-edge level inversion, ")
          .append("R2 pkg-SCC equal level, R3 container, R4 type-flag drift)\n\n");

        sb.append("-- Inputs --\n");
        if (sourcePaths.isEmpty()) {
            sb.append("(no source paths provided)\n");
        } else {
            for (String p : sourcePaths) sb.append("  ").append(p).append('\n');
        }

        sb.append('\n');
        sb.append("-- Model --\n");
        sb.append("  Packages:                ").append(packageCount).append('\n');
        sb.append("  Classes:                 ").append(classCount).append('\n');
        sb.append("  Dependencies:            ").append(dependencyCount).append('\n');
        sb.append("  MaxLevel:                ").append(maxLevel).append('\n');
        sb.append("  Heuristic back edges:    ").append(identifiedBackEdgeCount).append('\n');

        sb.append('\n');
        sb.append("-- Findings: ").append(findings.size()).append(" --\n");
        if (findings.isEmpty()) {
            sb.append("(none — algorithm output is internally consistent)\n");
            return sb.toString();
        }

        // Preserve insertion order of rule blocks; LinkedHashMap by ruleId
        // groups findings while letting us print rule headers in encounter
        // order (R1 → R2 → R3 → R4 in practice).
        Map<String, List<InvariantFinding>> grouped = new LinkedHashMap<>();
        for (InvariantFinding f : findings) {
            grouped.computeIfAbsent(f.ruleId(), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<InvariantFinding>> e : grouped.entrySet()) {
            String ruleId = e.getKey();
            List<InvariantFinding> group = e.getValue();
            sb.append('\n');
            sb.append('[').append(ruleId).append("] ").append(group.get(0).message()).append('\n');
            int idx = 0;
            for (InvariantFinding f : group) {
                idx++;
                if (idx > MAX_FINDINGS_PER_RULE) {
                    sb.append("  ... (").append(group.size() - MAX_FINDINGS_PER_RULE)
                      .append(" more ").append(ruleId).append(" findings omitted)\n");
                    break;
                }
                sb.append("  ").append(idx).append(". ")
                  .append(nullSafe(f.fromName(), "?"))
                  .append(" (L").append(f.fromLevel());
                if (f.fromContainer() != null) {
                    sb.append(", pkg=").append(f.fromContainer());
                }
                sb.append(')');
                if (f.toName() != null) {
                    sb.append("  →  ").append(f.toName())
                      .append(" (L").append(f.toLevel());
                    if (f.toContainer() != null) {
                        sb.append(", pkg=").append(f.toContainer());
                    }
                    sb.append(')');
                }
                sb.append('\n');
                if ("R1".equals(ruleId)) {
                    String reasonPrefix = "Level inversion across non-back-edge dependency: ";
                    String msg = f.message();
                    String reason = msg.startsWith(reasonPrefix)
                            ? msg.substring(reasonPrefix.length()) : msg;
                    sb.append("       reason: ").append(reason).append('\n');
                }
            }
        }

        sb.append('\n');
        sb.append("-- Reproducer hint --\n");
        sb.append("Feed the input file(s) above into the matching LanguageAnalyzer +\n");
        sb.append("LevelCalculator.calculate() + LayoutInvariantChecker.check();\n");
        sb.append("the listed (from → to) pairs should re-trigger the same findings.\n");
        return sb.toString();
    }

    private static String nullSafe(String s, String fallback) {
        return s == null ? fallback : s;
    }
}
