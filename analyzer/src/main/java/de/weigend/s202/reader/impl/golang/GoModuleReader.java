package de.weigend.s202.reader.impl.golang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Locates and parses the {@code go.mod} file for a project root.
 */
public class GoModuleReader {

    /**
     * Searches {@code startDir} and its ancestors for {@code go.mod} and
     * parses the module name and Go version from it.
     *
     * @throws IOException if no {@code go.mod} is found or cannot be read
     */
    public GoModuleInfo read(Path startDir) throws IOException {
        Path goMod = findGoMod(startDir);
        if (goMod == null) {
            throw new IOException("No go.mod found in or above: " + startDir);
        }
        return parse(goMod);
    }

    public GoModuleInfo parse(Path goModFile) throws IOException {
        List<String> lines = Files.readAllLines(goModFile);
        String moduleName = null;
        String goVersion  = null;
        for (String line : lines) {
            line = line.strip();
            if (line.startsWith("module ")) {
                moduleName = line.substring("module ".length()).strip();
            } else if (line.startsWith("go ")) {
                goVersion = line.substring("go ".length()).strip();
            }
        }
        if (moduleName == null || moduleName.isBlank()) {
            throw new IOException("No module directive found in: " + goModFile);
        }
        return new GoModuleInfo(moduleName, goVersion != null ? goVersion : "", goModFile.getParent());
    }

    private static Path findGoMod(Path dir) {
        Path current = dir.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("go.mod");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        return null;
    }
}
