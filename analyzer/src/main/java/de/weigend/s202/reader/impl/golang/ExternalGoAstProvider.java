package de.weigend.s202.reader.impl.golang;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * GoAstProvider backed by an external Go subprocess running {@code s202_go_ast.go}.
 *
 * <p>Go executable lookup order:
 * <ol>
 *   <li>System property {@code s202.go.executable}</li>
 *   <li>Environment variable {@code GO}</li>
 *   <li>Default {@code "go"}</li>
 * </ol>
 */
public class ExternalGoAstProvider implements GoAstProvider {

    private static final String HELPER_RESOURCE = "/go/s202_go_ast.go";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String goExecutable;

    public ExternalGoAstProvider() {
        this.goExecutable = findGoExecutable();
    }

    @Override
    public List<ParsedGoFile> parse(List<Path> files, String moduleName, Path moduleRoot)
            throws IOException, InterruptedException {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        Path helper = materializeHelper();
        Path input  = Files.createTempFile("s202-go-input-",  ".json");
        Path output = Files.createTempFile("s202-go-output-", ".json");

        try {
            MAPPER.writeValue(input.toFile(), Map.of(
                    "moduleName", moduleName,
                    "moduleRoot", moduleRoot.toAbsolutePath().toString(),
                    "files", files.stream()
                            .map(p -> p.toAbsolutePath().toString())
                            .toList()));

            Process process = new ProcessBuilder(
                    goExecutable, "run",
                    helper.toAbsolutePath().toString(),
                    input.toAbsolutePath().toString(),
                    output.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            String processOutput;
            try (InputStream out = process.getInputStream()) {
                processOutput = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Go AST helper failed (exit " + exit + ")"
                        + (processOutput.isBlank() ? "" : ":\n" + processOutput));
            }

            RawFileOutput[] raw = MAPPER.readValue(output.toFile(), RawFileOutput[].class);
            return toParseGoFiles(raw);

        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    // ── Helper materialisation ────────────────────────────────────────────────

    private static Path materializeHelper() throws IOException {
        // Use a stable directory so Go's build cache stays warm across runs.
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "s202-go-helper");
        Files.createDirectories(dir);
        Path helper = dir.resolve("s202_go_ast.go");
        try (InputStream in = ExternalGoAstProvider.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + HELPER_RESOURCE);
            byte[] content = in.readAllBytes();
            // Only overwrite if content changed (avoid triggering recompilation).
            if (!Files.exists(helper) || !java.util.Arrays.equals(Files.readAllBytes(helper), content)) {
                Files.write(helper, content);
            }
        }
        return helper;
    }

    // ── Executable discovery ──────────────────────────────────────────────────

    static String findGoExecutable() {
        String prop = System.getProperty("s202.go.executable");
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("GO");
        if (env != null && !env.isBlank()) return env;
        return "go";
    }

    // ── JSON → ParsedGoFile mapping ───────────────────────────────────────────

    private static List<ParsedGoFile> toParseGoFiles(RawFileOutput[] raw) {
        if (raw == null) return List.of();
        var result = new java.util.ArrayList<ParsedGoFile>(raw.length);
        for (RawFileOutput r : raw) {
            result.add(new ParsedGoFile(
                    r.filePath,
                    r.packageName,
                    r.importPath,
                    toImports(r.imports),
                    toTypes(r.types),
                    toFunctions(r.functions),
                    toVars(r.vars),
                    toCalls(r.calls)));
        }
        return result;
    }

    private static List<ParsedGoFile.ImportDecl> toImports(RawImport[] raw) {
        if (raw == null) return List.of();
        var list = new java.util.ArrayList<ParsedGoFile.ImportDecl>(raw.length);
        for (RawImport r : raw) list.add(new ParsedGoFile.ImportDecl(r.alias, r.path));
        return list;
    }

    private static List<ParsedGoFile.TypeDecl> toTypes(RawType[] raw) {
        if (raw == null) return List.of();
        var list = new java.util.ArrayList<ParsedGoFile.TypeDecl>(raw.length);
        for (RawType r : raw) {
            list.add(new ParsedGoFile.TypeDecl(
                    r.name, r.kind,
                    r.typeParams == null ? List.of() : List.of(r.typeParams),
                    r.embeds    == null ? List.of() : List.of(r.embeds),
                    toFields(r.fields)));
        }
        return list;
    }

    private static List<ParsedGoFile.FieldDecl> toFields(RawField[] raw) {
        if (raw == null) return List.of();
        var list = new java.util.ArrayList<ParsedGoFile.FieldDecl>(raw.length);
        for (RawField r : raw)
            list.add(new ParsedGoFile.FieldDecl(r.name, r.typeRef, r.qualifiedPkg));
        return list;
    }

    private static List<ParsedGoFile.FunctionDecl> toFunctions(RawFunction[] raw) {
        if (raw == null) return List.of();
        var list = new java.util.ArrayList<ParsedGoFile.FunctionDecl>(raw.length);
        for (RawFunction r : raw) {
            list.add(new ParsedGoFile.FunctionDecl(
                    r.name, r.receiver,
                    r.typeParams == null ? List.of() : List.of(r.typeParams),
                    r.params     == null ? List.of() : List.of(r.params),
                    r.results    == null ? List.of() : List.of(r.results)));
        }
        return list;
    }

    private static List<ParsedGoFile.VarDecl> toVars(RawVar[] raw) {
        if (raw == null) return List.of();
        var list = new java.util.ArrayList<ParsedGoFile.VarDecl>(raw.length);
        for (RawVar r : raw)
            list.add(new ParsedGoFile.VarDecl(r.name, r.typeRef, r.qualifiedPkg));
        return list;
    }

    private static List<ParsedGoFile.CallRef> toCalls(RawCall[] raw) {
        if (raw == null) return List.of();
        var list = new java.util.ArrayList<ParsedGoFile.CallRef>(raw.length);
        for (RawCall r : raw)
            list.add(new ParsedGoFile.CallRef(r.callerFunction, r.calleePkg, r.calleeName, r.isNewPattern));
        return list;
    }

    // ── Raw JSON DTOs (mirrors s202_go_ast.go output) ────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawFileOutput {
        public String filePath, packageName, importPath;
        public RawImport[]   imports;
        public RawType[]     types;
        public RawFunction[] functions;
        public RawVar[]      vars;
        public RawCall[]     calls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawImport {
        public String alias, path;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawType {
        public String   name, kind;
        public String[] typeParams, embeds;
        public RawField[] fields;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawField {
        public String name, typeRef, qualifiedPkg;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawFunction {
        public String   name, receiver;
        public String[] typeParams, params, results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawVar {
        public String name, typeRef, qualifiedPkg;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawCall {
        public String  callerFunction, calleePkg, calleeName;
        @JsonProperty("isNewPattern")
        public boolean isNewPattern;
    }
}
