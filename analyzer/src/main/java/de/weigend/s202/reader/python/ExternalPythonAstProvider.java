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
package de.weigend.s202.reader.python;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Python AST provider backed by CPython's built-in {@code ast} module.
 */
public class ExternalPythonAstProvider implements PythonAstProvider {

    private static final String HELPER_RESOURCE = "/python/s202_py_ast.py";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String pythonExecutable;

    public ExternalPythonAstProvider() {
        this(findPythonExecutable());
    }

    public ExternalPythonAstProvider(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    @Override
    public List<ParsedPythonModule> parse(List<ModuleSource> sources)
            throws IOException, InterruptedException {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }

        Path helper = materializeHelper();
        Path input = Files.createTempFile("s202-python-input-", ".json");
        Path output = Files.createTempFile("s202-python-output-", ".json");
        try {
            MAPPER.writeValue(input.toFile(), Map.of(
                    "files", sources.stream()
                            .map(source -> Map.of(
                                    "moduleName", source.moduleName(),
                                    "path", source.sourcePath().toAbsolutePath().toString()))
                            .toList()));

            Process process = new ProcessBuilder(
                    pythonExecutable,
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
                throw new IOException("Python AST helper failed with exit code " + exit
                        + (processOutput.isBlank() ? "" : ":\n" + processOutput));
            }

            HelperOutput parsed = MAPPER.readValue(output.toFile(), HelperOutput.class);
            if (parsed.errors() != null) {
                for (ParseError error : parsed.errors()) {
                    System.err.println("Warning: Could not parse Python file "
                            + error.path() + ": " + error.message());
                }
            }
            return parsed.modules() == null ? List.of() : parsed.modules();
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    private static Path materializeHelper() throws IOException {
        Path helper = Files.createTempFile("s202-py-ast-", ".py");
        helper.toFile().deleteOnExit();
        try (InputStream in = ExternalPythonAstProvider.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + HELPER_RESOURCE);
            }
            Files.write(helper, in.readAllBytes());
        }
        return helper;
    }

    private static String findPythonExecutable() {
        String configured = System.getProperty("s202.python.executable");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String env = System.getenv("PYTHON");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "python3";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HelperOutput(List<ParsedPythonModule> modules, List<ParseError> errors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ParseError(String moduleName, String path, String message) {
    }
}
