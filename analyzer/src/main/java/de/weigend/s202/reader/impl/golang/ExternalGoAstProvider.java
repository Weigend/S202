package de.weigend.s202.reader.impl.golang;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * GoAstProvider that delegates to an external Go subprocess running
 * {@code s202_go_ast.go}. Not yet implemented — placeholder for the
 * prototype phase.
 */
public class ExternalGoAstProvider implements GoAstProvider {

    @Override
    public List<ParsedGoFile> parse(List<Path> files, String moduleName, Path moduleRoot)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "ExternalGoAstProvider is not yet implemented. " +
                "Use GoSourceAnalyzer(GoAstProvider, ...) with a test provider.");
    }
}
