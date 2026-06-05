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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Parser boundary for Python source. Implementations may use CPython, a Java
 * parser, or any other parser as long as they return the parser-level DTOs.
 */
public interface PythonAstProvider {

    List<ParsedPythonModule> parse(List<ModuleSource> sources) throws IOException, InterruptedException;

    record ModuleSource(String moduleName, Path sourcePath) {
    }
}
