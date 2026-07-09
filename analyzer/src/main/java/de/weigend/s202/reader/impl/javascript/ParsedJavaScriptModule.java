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
package de.weigend.s202.reader.impl.javascript;

import java.util.List;

/**
 * Parser-level DTO for one JavaScript/ES module — the JS counterpart of
 * {@code ParsedPythonModule}. Produced by a {@link JavaScriptAstProvider}
 * and consumed by the dependency resolver.
 */
public record ParsedJavaScriptModule(
        String moduleName,
        String sourcePath,
        List<ImportRef> imports,
        List<ClassDef> classes,
        List<FunctionDef> functions,
        List<UsageRef> usages) {

    /**
     * A binding introduced by an {@code import}/{@code export … from}
     * statement.
     *
     * @param kind         one of {@code named}, {@code namespace},
     *                     {@code default}, {@code side_effect}, {@code dynamic}
     * @param source       the module specifier ({@code './sky.js'},
     *                     {@code 'three'} …)
     * @param importedName the name in the source module ({@code null} for
     *                     namespace / side-effect / dynamic imports)
     * @param localName    the local binding ({@code null} for side-effect /
     *                     dynamic imports)
     */
    public record ImportRef(String kind, String source, String importedName, String localName, int line) {
    }

    /** A {@code class Name [extends Super]} declaration. */
    public record ClassDef(String name, String superName, int line) {
    }

    /** A named top-level function or method (for the module's method count). */
    public record FunctionDef(String name, int line) {
    }

    /**
     * A use of a local binding as a value — a call or a {@code new}. The head
     * identifier ({@code localName}) is matched against the import aliases; the
     * member path is what follows (e.g. {@code Mesh} in {@code THREE.Mesh}).
     */
    public record UsageRef(String localName, boolean constructor, String memberPath, int line) {
    }
}
