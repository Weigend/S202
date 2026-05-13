package de.weigend.s202.ui.consistency;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.HierarchicalLayeredArchitectureBuilder;
import de.weigend.s202.ui.model.ArchitectureNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Optional dev-build hook that runs the
 * {@link ArchitectureConsistencyChecker} on every freshly-built
 * architecture and reports the result. Gated by the system property
 * {@code s202.dev.architectureCheck}:
 *
 * <ul>
 *   <li>unset or empty → no-op (default for shipped builds);</li>
 *   <li>{@code true} or {@code warn} → run the check, log a WARN with
 *       the full discrepancy list when something doesn't match;</li>
 *   <li>{@code throw} → log the WARN and additionally throw an
 *       {@link IllegalStateException}, useful for failing CI when the
 *       budget regresses.</li>
 * </ul>
 *
 * <p>Enable from the command line:
 * {@code mvn javafx:run -Ds202.dev.architectureCheck=true}
 * — or set the JVM arg in the IDE run configuration.
 */
public final class ArchitectureConsistencyDevHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchitectureConsistencyDevHook.class);
    private static final String FLAG = "s202.dev.architectureCheck";

    private ArchitectureConsistencyDevHook() {}

    public static void runIfEnabled(DomainModel domain, ArchitectureNode uiRoot) {
        String setting = System.getProperty(FLAG, "");
        if (setting.isBlank()) {
            return;
        }
        if (domain == null || uiRoot == null) {
            return;
        }

        Architecture arch = new HierarchicalLayeredArchitectureBuilder().build(domain);
        List<ArchitectureConsistencyChecker.Discrepancy> diffs =
                new ArchitectureConsistencyChecker().check(arch, uiRoot);

        if (diffs.isEmpty()) {
            LOGGER.info("Architecture consistency check: PASS — {} violations detected by the model",
                    arch.violations().size());
            return;
        }

        StringBuilder report = new StringBuilder(
                "Architecture consistency mismatch (" + diffs.size() + " discrepancies):");
        for (ArchitectureConsistencyChecker.Discrepancy d : diffs) {
            report.append("\n  ").append(d.path()).append(" — ").append(d.message());
        }
        LOGGER.warn(report.toString());

        if ("throw".equalsIgnoreCase(setting)) {
            throw new IllegalStateException(
                    "Architecture consistency mismatch: " + diffs.size() + " discrepancies");
        }
    }
}
