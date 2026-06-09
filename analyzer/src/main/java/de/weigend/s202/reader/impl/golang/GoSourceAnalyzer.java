package de.weigend.s202.reader.impl.golang;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.LanguageAnalyzer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link LanguageAnalyzer} implementation for Go source trees.
 *
 * <p>Analysis pipeline:
 * <ol>
 *   <li>Locate and parse {@code go.mod} to determine the module name.</li>
 *   <li>Walk the module directory tree to collect {@code .go} files
 *       ({@code vendor/} excluded).</li>
 *   <li>Delegate AST extraction to the injected {@link GoAstProvider}.</li>
 *   <li>Resolve the parsed AST data into a {@link DependencyModel} via
 *       {@link GoDependencyResolver}.</li>
 * </ol>
 *
 * <p>An external Go installation is required (checked via {@code go version}).
 * Configure the executable via the system property {@code s202.go.executable},
 * the environment variable {@code GO}, or rely on the default {@code "go"}.
 */
@Singleton
public class GoSourceAnalyzer implements LanguageAnalyzer {

    private final GoAstProvider    astProvider;
    private final GoModuleReader   moduleReader;
    private final GoFileDiscovery  fileDiscovery;

    /** Default constructor — uses the real external Go subprocess. */
    public GoSourceAnalyzer() {
        this(new ExternalGoAstProvider(), new GoModuleReader(), new GoFileDiscovery());
    }

    /** Injectable constructor for tests. */
    GoSourceAnalyzer(GoAstProvider astProvider,
                     GoModuleReader moduleReader,
                     GoFileDiscovery fileDiscovery) {
        this.astProvider   = astProvider;
        this.moduleReader  = moduleReader;
        this.fileDiscovery = fileDiscovery;
    }

    @Override
    public String displayName() {
        return "Go";
    }

    @Override
    public DependencyModel analyze(List<Path> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one path required");
        }
        Path root = inputs.get(0);
        return analyze(root);
    }

    public DependencyModel analyze(Path projectRoot) throws IOException {
        GoModuleInfo moduleInfo = moduleReader.read(projectRoot);
        List<Path>   goFiles   = fileDiscovery.discover(moduleInfo.moduleRoot());
        try {
            List<ParsedGoFile> parsed = astProvider.parse(
                    goFiles, moduleInfo.moduleName(), moduleInfo.moduleRoot());
            return new GoDependencyResolver(moduleInfo.moduleName(), parsed).resolve();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Go AST parsing interrupted", e);
        }
    }
}
