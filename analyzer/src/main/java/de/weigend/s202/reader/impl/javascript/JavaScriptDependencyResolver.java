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

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps parsed ES modules onto the {@link DependencyModel} — the JS counterpart
 * of {@code PythonDependencyResolver}. Static {@code import} statements become
 * the module dependency graph; {@code class … extends} and value uses of
 * imported bindings ({@code new X()}, {@code fn()}) refine the edge kinds.
 *
 * <p>Only edges between modules that exist in the project are recorded;
 * external packages (bare specifiers such as {@code 'three'}) resolve to no
 * project module and are dropped, exactly as unresolved Python imports are.</p>
 */
final class JavaScriptDependencyResolver {

    private final DependencyModel model;
    private final List<ParsedJavaScriptModule> modules;
    /** Normalised absolute file path (without extension) → module name. */
    private final Map<String, String> moduleByPath = new LinkedHashMap<>();

    JavaScriptDependencyResolver(DependencyModel model, List<ParsedJavaScriptModule> modules) {
        this.model = model;
        this.modules = modules == null ? List.of() : modules;
        for (ParsedJavaScriptModule module : this.modules) {
            moduleByPath.put(stripExtension(normalize(module.sourcePath())), module.moduleName());
        }
    }

    void populate() {
        for (ParsedJavaScriptModule module : modules) {
            DependencyModel.ClassInfo classInfo = model.getClass(module.moduleName());
            if (classInfo == null) {
                continue;
            }
            addMethods(classInfo, module);
            Map<String, String> aliases = addImports(classInfo, module);
            addInheritance(classInfo, module, aliases);
            addUsages(classInfo, module, aliases);
        }
    }

    private void addMethods(DependencyModel.ClassInfo classInfo, ParsedJavaScriptModule module) {
        for (ParsedJavaScriptModule.FunctionDef fn : module.functions()) {
            classInfo.addMethod(fn.name(), "()");
        }
    }

    /**
     * Records an IMPORTS edge per import whose specifier resolves to a project
     * module and returns the local-binding → module-name alias map used for
     * inheritance and usage resolution.
     */
    private Map<String, String> addImports(DependencyModel.ClassInfo classInfo, ParsedJavaScriptModule module) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (ParsedJavaScriptModule.ImportRef imp : module.imports()) {
            String target = resolveModule(module, imp.source());
            if (target == null) {
                continue;                              // external / unresolved specifier
            }
            addDependency(classInfo, target, EdgeKind.IMPORTS);
            if (imp.localName() != null && !imp.localName().isBlank()) {
                aliases.put(imp.localName(), target);
            }
        }
        return aliases;
    }

    private void addInheritance(DependencyModel.ClassInfo classInfo, ParsedJavaScriptModule module,
                                Map<String, String> aliases) {
        for (ParsedJavaScriptModule.ClassDef cls : module.classes()) {
            if (cls.superName() == null) {
                continue;
            }
            String head = headIdentifier(cls.superName());
            String target = aliases.get(head);
            if (target != null) {
                addDependency(classInfo, target, EdgeKind.EXTENDS);
            }
        }
    }

    private void addUsages(DependencyModel.ClassInfo classInfo, ParsedJavaScriptModule module,
                           Map<String, String> aliases) {
        for (ParsedJavaScriptModule.UsageRef usage : module.usages()) {
            String target = aliases.get(usage.localName());
            if (target == null) {
                continue;
            }
            addDependency(classInfo, target, usage.constructor() ? EdgeKind.INSTANTIATES : EdgeKind.CALLS);
        }
    }

    private void addDependency(DependencyModel.ClassInfo from, String to, EdgeKind kind) {
        if (from == null || to == null || to.isBlank() || from.fullName.equals(to)) {
            return;
        }
        if (model.getClass(to) == null) {
            return;
        }
        from.addDependency(to, kind);
    }

    /**
     * Resolves an import specifier to a project module name, or {@code null}
     * for external/unresolvable specifiers. Relative specifiers are resolved
     * against the importing module's directory; bare specifiers (npm packages)
     * never match a project file.
     */
    private String resolveModule(ParsedJavaScriptModule importer, String specifier) {
        if (specifier == null || specifier.isBlank()) {
            return null;
        }
        if (!(specifier.startsWith("./") || specifier.startsWith("../") || specifier.startsWith("/"))) {
            return null;                               // bare specifier → npm package
        }
        Path importerDir = Paths.get(normalize(importer.sourcePath())).getParent();
        if (importerDir == null) {
            return null;
        }
        Path resolved = importerDir.resolve(specifier).normalize();
        String base = stripExtension(resolved.toString());
        String direct = moduleByPath.get(base);
        if (direct != null) {
            return direct;
        }
        // Directory import: './foo' → './foo/index.js'
        return moduleByPath.get(stripExtension(resolved.resolve("index").toString()));
    }

    private static String normalize(String path) {
        return Paths.get(path).toAbsolutePath().normalize().toString();
    }

    private static String stripExtension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    private static String headIdentifier(String expression) {
        String trimmed = expression.trim();
        int dot = trimmed.indexOf('.');
        return dot < 0 ? trimmed : trimmed.substring(0, dot);
    }
}
