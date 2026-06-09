package de.weigend.s202.reader.impl.golang;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Pluggable AST provider for Go source files.
 * The default implementation delegates to an external Go subprocess.
 * Tests inject an in-memory implementation.
 */
public interface GoAstProvider {

    /**
     * Parses the given Go files and returns one {@link ParsedGoFile} per file.
     *
     * @param files      Go source files to parse
     * @param moduleName the Go module name from go.mod (used for import-path resolution)
     * @param moduleRoot the directory containing go.mod
     */
    List<ParsedGoFile> parse(List<Path> files, String moduleName, Path moduleRoot)
            throws IOException, InterruptedException;
}
