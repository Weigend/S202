package de.weigend.s202.reader.impl.golang;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks a Go module directory tree and collects {@code .go} source files,
 * excluding standard non-source directories.
 */
public class GoFileDiscovery {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "vendor", ".git", "testdata", ".idea", ".vscode", "node_modules");

    /**
     * Returns all {@code .go} files reachable from {@code moduleRoot},
     * excluding {@code vendor/} and other non-source directories.
     */
    public List<Path> discover(Path moduleRoot) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(moduleRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (EXCLUDED_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".go")) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }
}
