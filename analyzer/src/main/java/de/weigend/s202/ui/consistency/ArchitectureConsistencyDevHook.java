package de.weigend.s202.ui.consistency;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.HierarchicalLayeredArchitecture;
import de.weigend.s202.domain.architecture.HierarchicalLayeredArchitectureBuilder;
import de.weigend.s202.ui.model.ArchitectureNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dev hook that runs the {@link ArchitectureConsistencyChecker} on
 * every freshly-built architecture and logs the result. Currently
 * always-on while we migrate the UI off its private violation logic —
 * once the legacy path is removed and consistency stops being a
 * possibility worth checking, this whole class goes away.
 *
 * <p>On a match the result is logged at INFO. On a mismatch a WARN
 * with the full discrepancy list is emitted; the app keeps running.
 */
public final class ArchitectureConsistencyDevHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchitectureConsistencyDevHook.class);

    private ArchitectureConsistencyDevHook() {}

    public static void runIfEnabled(DomainModel domain, ArchitectureNode uiRoot) {
        if (domain == null || uiRoot == null) {
            return;
        }

        Architecture arch = new HierarchicalLayeredArchitectureBuilder().build(domain);
        List<ArchitectureConsistencyChecker.Discrepancy> diffs =
                new ArchitectureConsistencyChecker().check(arch, uiRoot);

        if (diffs.isEmpty()) {
            int violationCount = arch.violations().size();
            int tangleCount = arch instanceof HierarchicalLayeredArchitecture hla
                    ? hla.tangles().size() : 0;
            LOGGER.info("Architecture consistency check: PASS — {} violations, {} tangles",
                    violationCount, tangleCount);
            return;
        }

        StringBuilder report = new StringBuilder(
                "Architecture consistency mismatch (" + diffs.size() + " discrepancies):");
        for (ArchitectureConsistencyChecker.Discrepancy d : diffs) {
            report.append("\n  ").append(d.path()).append(" — ").append(d.message());
        }
        LOGGER.warn(report.toString());
    }
}
