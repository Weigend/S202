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

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.PackageHierarchyBuilder;


import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Analyzes Python source trees and maps modules/files onto S202's DependencyModel.
 */
public class PythonSourceAnalyzer {

    private static final List<String> COMMON_SOURCE_ROOTS = List.of("src", "lib");

    private static final Set<String> EXCLUDED_DIR_NAMES = Set.of(
            ".git", ".hg", ".svn",
            ".venv", "venv", "env", ".tox",
            "__pycache__", ".pytest_cache", ".mypy_cache",
            "site-packages", "dist-packages", "build", "dist");

    private static final Set<String> PYTHON_INSTALL_ROOT_NAMES = Set.of("site-packages", "dist-packages");

    private final PythonAstProvider astProvider;

    public PythonSourceAnalyzer() {
        this(new ExternalPythonAstProvider());
    }

    public PythonSourceAnalyzer(PythonAstProvider astProvider) {
        this.astProvider = Objects.requireNonNull(astProvider, "astProvider");
    }

    public DependencyModel analyze(Path sourceRootOrProjectRoot) throws IOException {
        return analyze(List.of(sourceRootOrProjectRoot));
    }

    public DependencyModel analyze(List<Path> roots) throws IOException {
        try {
            return analyzeInterruptibly(roots);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while analyzing Python sources", e);
        }
    }

    private DependencyModel analyzeInterruptibly(List<Path> roots) throws IOException, InterruptedException {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("at least one Python source root is required");
        }

        List<SourceRoot> sourceRoots = discoverSourceRoots(roots);
        List<ModuleFile> modules = discoverModules(sourceRoots);

        DependencyModel model = new DependencyModel();
        Map<String, ModuleFile> moduleFiles = new LinkedHashMap<>();
        for (ModuleFile module : modules) {
            moduleFiles.put(module.moduleName(), module);
            model.addClass(module.moduleName(), new DependencyModel.ClassInfo(
                    module.moduleName(), module.simpleName(), module.packageName(), false));
        }

        List<PythonAstProvider.ModuleSource> sources = modules.stream()
                .map(module -> new PythonAstProvider.ModuleSource(module.moduleName(), module.path()))
                .toList();
        List<ParsedPythonModule> parsedModules = astProvider.parse(sources);
        PythonDependencyResolver resolver = new PythonDependencyResolver(model, parsedModules);
        resolver.populate();

        PackageHierarchyBuilder.buildPackageHierarchy(model);
        return model;
    }

    private static List<SourceRoot> discoverSourceRoots(List<Path> roots) throws IOException {
        LinkedHashSet<SourceRoot> result = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path normalized = root.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized) && normalized.getFileName().toString().endsWith(".py")) {
                Path parent = normalized.getParent();
                result.add(new SourceRoot(parent, parent));
                continue;
            }
            if (!Files.isDirectory(normalized)) {
                throw new IOException("Python source root does not exist or is not a directory: " + normalized);
            }

            boolean foundCommonSourceRoot = false;
            for (String commonRoot : COMMON_SOURCE_ROOTS) {
                Path candidate = normalized.resolve(commonRoot);
                if (Files.isDirectory(candidate) && containsPythonFile(candidate)) {
                    Path sourceRoot = candidate.toAbsolutePath().normalize();
                    result.add(new SourceRoot(sourceRoot, sourceRoot));
                    foundCommonSourceRoot = true;
                }
            }
            if (!foundCommonSourceRoot) {
                // If the user selects the package directory itself, e.g.
                // /usr/lib/python3/dist-packages/ansible, scan only that
                // subtree but build module FQNs relative to the parent so
                // imports like "ansible.module_utils..." resolve.
                Path moduleRoot = isSelectedPackageDirectory(normalized)
                        ? normalized.getParent()
                        : normalized;
                result.add(new SourceRoot(normalized, moduleRoot.toAbsolutePath().normalize()));
            }
        }
        return result.stream().toList();
    }

    private static boolean isSelectedPackageDirectory(Path directory) {
        if (directory == null || directory.getParent() == null) {
            return false;
        }
        if (Files.exists(directory.resolve("__init__.py"))) {
            return true;
        }
        Path parentName = directory.getParent().getFileName();
        return parentName != null && PYTHON_INSTALL_ROOT_NAMES.contains(parentName.toString());
    }

    private static boolean containsPythonFile(Path root) throws IOException {
        return !collectPythonFiles(root).isEmpty();
    }

    private static List<ModuleFile> discoverModules(List<SourceRoot> sourceRoots) throws IOException {
        List<ModuleFile> modules = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            String syntheticRootPackage = syntheticRootPackage(sourceRoot.moduleRoot());
            for (Path file : collectPythonFiles(sourceRoot.scanRoot())) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!seen.add(normalized)) {
                    continue;
                }
                modules.add(toModuleFile(sourceRoot.moduleRoot(), normalized, syntheticRootPackage));
            }
        }
        modules.sort(Comparator.comparing(ModuleFile::moduleName));
        return modules;
    }

    private static List<Path> collectPythonFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIR_NAMES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".py")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static ModuleFile toModuleFile(Path sourceRoot, Path file, String syntheticRootPackage) {
        Path relative = sourceRoot.toAbsolutePath().normalize().relativize(file);
        String fileName = relative.getFileName().toString();
        String moduleSimpleName = fileName.substring(0, fileName.length() - ".py".length());

        Path parent = relative.getParent();
        String packageName;
        if (parent == null) {
            packageName = syntheticRootPackage;
        } else {
            packageName = dottedPath(parent);
        }

        String moduleName = packageName + "." + moduleSimpleName;
        return new ModuleFile(moduleName, packageName, moduleSimpleName, file);
    }

    private static String dottedPath(Path path) {
        List<String> parts = new ArrayList<>();
        for (Path part : path) {
            parts.add(sanitizeIdentifier(part.toString()));
        }
        return String.join(".", parts);
    }

    private static String syntheticRootPackage(Path sourceRoot) {
        Path name = sourceRoot.getFileName();
        if (name != null && !"src".equalsIgnoreCase(name.toString())) {
            return sanitizeIdentifier(name.toString());
        }
        Path parent = sourceRoot.getParent();
        if (parent != null && parent.getFileName() != null) {
            return sanitizeIdentifier(parent.getFileName().toString());
        }
        return "python";
    }

    private static String sanitizeIdentifier(String raw) {
        String normalized = raw == null || raw.isBlank() ? "python" : raw;
        normalized = normalized.replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isBlank()) {
            normalized = "python";
        }
        if (!Character.isJavaIdentifierStart(normalized.charAt(0))) {
            normalized = "pkg_" + normalized;
        }
        return normalized;
    }

    private record ModuleFile(String moduleName, String packageName, String simpleName, Path path) {
    }

    private record SourceRoot(Path scanRoot, Path moduleRoot) {
    }

    private static final class PythonDependencyResolver {
        private final DependencyModel model;
        private final Map<String, ParsedPythonModule> modules = new LinkedHashMap<>();
        private final SymbolIndex symbolIndex;

        PythonDependencyResolver(DependencyModel model, List<ParsedPythonModule> parsedModules) {
            this.model = model;
            if (parsedModules != null) {
                for (ParsedPythonModule module : parsedModules) {
                    modules.put(module.moduleName(), module);
                }
            }
            this.symbolIndex = new SymbolIndex(modules.values());
        }

        void populate() {
            addDeclaredMethods();
            for (ParsedPythonModule module : modules.values()) {
                DependencyModel.ClassInfo classInfo = model.getClass(module.moduleName());
                if (classInfo == null) {
                    continue;
                }
                ModuleContext context = new ModuleContext(module, classInfo, buildAliases(module));
                addClassStructureDependencies(context);
                addAnnotationDependencies(context);
                context.collectClassFieldTypes();
                addCallDependencies(context);
            }
        }

        private void addDeclaredMethods() {
            for (ParsedPythonModule module : modules.values()) {
                DependencyModel.ClassInfo classInfo = model.getClass(module.moduleName());
                if (classInfo == null) {
                    continue;
                }
                for (ParsedPythonModule.ScopeDef scope : module.scopes()) {
                    classInfo.addMethod(scope.name(), descriptor(scope.descriptor()));
                }
            }
        }

        private Map<String, AliasTarget> buildAliases(ParsedPythonModule module) {
            Map<String, AliasTarget> aliases = new LinkedHashMap<>();
            DependencyModel.ClassInfo classInfo = model.getClass(module.moduleName());
            for (ParsedPythonModule.ImportRef importRef : module.imports()) {
                if ("import".equals(importRef.kind())) {
                    String importedModule = symbolIndex.resolveModule(importRef.module());
                    if (importedModule != null) {
                        addDependency(classInfo, importedModule, EdgeKind.IMPORTS);
                        if (importRef.alias() != null && !importRef.alias().isBlank()) {
                            aliases.put(importRef.alias(), AliasTarget.module(importedModule));
                        }
                    }
                    continue;
                }

                String base = resolveImportBase(module, importRef);
                if (base == null || base.isBlank()) {
                    continue;
                }
                if (importRef.star()) {
                    String starModule = symbolIndex.resolveModule(base);
                    if (starModule != null) {
                        addDependency(classInfo, starModule, EdgeKind.IMPORTS);
                    }
                    continue;
                }

                String name = importRef.name();
                String alias = importRef.alias() == null || importRef.alias().isBlank()
                        ? name : importRef.alias();
                String moduleCandidate = base + "." + name;
                String resolvedModuleCandidate = symbolIndex.resolveModule(moduleCandidate);
                if (resolvedModuleCandidate != null) {
                    aliases.put(alias, AliasTarget.module(resolvedModuleCandidate));
                    addDependency(classInfo, resolvedModuleCandidate, EdgeKind.IMPORTS);
                } else {
                    String resolvedBase = symbolIndex.resolveModule(base);
                    if (resolvedBase == null) {
                        continue;
                    }
                    Symbol symbol = symbolIndex.symbolInModule(resolvedBase, name);
                    if (symbol != null) {
                        aliases.put(alias, AliasTarget.symbol(symbol));
                        addDependency(classInfo, symbol.moduleName(), EdgeKind.IMPORTS);
                    } else {
                        aliases.put(alias, AliasTarget.moduleMember(resolvedBase, name));
                        addDependency(classInfo, resolvedBase, EdgeKind.IMPORTS);
                    }
                }
            }
            return aliases;
        }

        private String resolveImportBase(ParsedPythonModule module, ParsedPythonModule.ImportRef importRef) {
            String imported = importRef.module() == null ? "" : importRef.module();
            if (importRef.level() <= 0) {
                return imported;
            }
            String base = packageName(module.moduleName());
            for (int i = 1; i < importRef.level(); i++) {
                base = packageName(base);
                if (base == null) {
                    return imported.isBlank() ? null : imported;
                }
            }
            if (imported.isBlank()) {
                return base;
            }
            return base == null || base.isBlank() ? imported : base + "." + imported;
        }

        private void addClassStructureDependencies(ModuleContext context) {
            for (ParsedPythonModule.ClassDef cls : context.module.classes()) {
                for (String base : cls.bases()) {
                    ResolvedTarget target = context.resolveSymbol(base);
                    if (target != null) {
                        addDependency(context.classInfo, target.moduleName(), EdgeKind.EXTENDS);
                    }
                }
                for (String decorator : cls.decorators()) {
                    ResolvedTarget target = context.resolveSymbol(decorator);
                    if (target != null) {
                        addDependency(context.classInfo, target.moduleName(), EdgeKind.USES);
                    }
                }
            }
        }

        private void addAnnotationDependencies(ModuleContext context) {
            for (ParsedPythonModule.ScopeDef scope : context.module.scopes()) {
                for (String annotation : scope.annotations()) {
                    ResolvedTarget target = context.resolveSymbol(annotation);
                    if (target != null) {
                        addDependency(context.classInfo, target.moduleName(), EdgeKind.USES);
                    }
                }
                for (ParsedPythonModule.AssignmentRef assignment : scope.assignments()) {
                    if (assignment.annotation() == null || assignment.annotation().isBlank()) {
                        continue;
                    }
                    ResolvedTarget target = context.resolveSymbol(assignment.annotation());
                    if (target != null) {
                        addDependency(context.classInfo, target.moduleName(), EdgeKind.USES);
                    }
                }
            }
        }

        private void addCallDependencies(ModuleContext context) {
            for (ParsedPythonModule.ScopeDef scope : context.module.scopes()) {
                DependencyModel.MethodInfo methodInfo = context.classInfo.getMethod(
                        scope.name(), descriptor(scope.descriptor()));
                if (methodInfo == null) {
                    continue;
                }
                ScopeContext scopeContext = context.scopeContext(scope);
                for (ParsedPythonModule.CallRef call : scope.calls()) {
                    ResolvedCall resolved = context.resolveCall(call.expression(), scopeContext);
                    if (resolved == null) {
                        continue;
                    }
                    addDependency(context.classInfo, resolved.moduleName(), resolved.kind());
                    String methodCall = resolved.moduleName() + "." + resolved.methodPath();
                    methodInfo.methodCalls.merge(methodCall, 1, Integer::sum);
                    methodInfo.methodCallDescriptors
                            .computeIfAbsent(methodCall, ignored -> new LinkedHashSet<>())
                            .add(descriptorFor(resolved.moduleName(), resolved.methodPath()));
                }
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

        private String descriptorFor(String moduleName, String methodPath) {
            ParsedPythonModule module = modules.get(moduleName);
            if (module == null) {
                return "()";
            }
            for (ParsedPythonModule.ScopeDef scope : module.scopes()) {
                if (scope.name().equals(methodPath)) {
                    return descriptor(scope.descriptor());
                }
            }
            return "()";
        }

        private static String descriptor(String descriptor) {
            return descriptor == null || descriptor.isBlank() ? "()" : descriptor;
        }

        private static String packageName(String fqn) {
            if (fqn == null) {
                return null;
            }
            int dot = fqn.lastIndexOf('.');
            return dot < 0 ? null : fqn.substring(0, dot);
        }

        private final class ModuleContext {
            final ParsedPythonModule module;
            final DependencyModel.ClassInfo classInfo;
            final Map<String, AliasTarget> aliases;
            final Map<String, Map<String, ResolvedTarget>> classFieldTypes = new HashMap<>();

            ModuleContext(ParsedPythonModule module,
                          DependencyModel.ClassInfo classInfo,
                          Map<String, AliasTarget> aliases) {
                this.module = module;
                this.classInfo = classInfo;
                this.aliases = aliases;
            }

            void collectClassFieldTypes() {
                for (ParsedPythonModule.ScopeDef scope : module.scopes()) {
                    if (scope.className() == null) {
                        continue;
                    }
                    ScopeContext scopeContext = scopeContext(scope);
                    Map<String, ResolvedTarget> fields = classFieldTypes.computeIfAbsent(
                            scope.className(), ignored -> new LinkedHashMap<>());
                    for (ParsedPythonModule.AssignmentRef assignment : scope.assignments()) {
                        if (assignment.target() == null || !assignment.target().startsWith("self.")) {
                            continue;
                        }
                        String fieldName = assignment.target().substring("self.".length());
                        ResolvedTarget type = assignmentType(assignment, scopeContext);
                        if (type != null && type.classSymbol()) {
                            fields.put(fieldName, type);
                        }
                    }
                }
            }

            ScopeContext scopeContext(ParsedPythonModule.ScopeDef scope) {
                Map<String, ResolvedTarget> localTypes = new LinkedHashMap<>();
                for (Map.Entry<String, String> param : scope.parameterAnnotations().entrySet()) {
                    ResolvedTarget target = resolveSymbol(param.getValue());
                    if (target != null && target.classSymbol()) {
                        localTypes.put(param.getKey(), target);
                    }
                }

                ScopeContext context = new ScopeContext(scope, localTypes);
                for (ParsedPythonModule.AssignmentRef assignment : scope.assignments()) {
                    if (assignment.target() == null || assignment.target().contains(".")) {
                        continue;
                    }
                    ResolvedTarget type = assignmentType(assignment, context);
                    if (type != null && type.classSymbol()) {
                        context.localTypes().put(assignment.target(), type);
                    }
                }
                return context;
            }

            private ResolvedTarget assignmentType(ParsedPythonModule.AssignmentRef assignment,
                                                  ScopeContext context) {
                if (assignment.annotation() != null && !assignment.annotation().isBlank()) {
                    ResolvedTarget target = resolveSymbol(assignment.annotation());
                    if (target != null) {
                        return target;
                    }
                }
                if (assignment.value() == null || assignment.value().isBlank()) {
                    return null;
                }
                if (!assignment.valueIsCall()) {
                    return context.localTypes().get(assignment.value());
                }
                ResolvedCall call = resolveCall(assignment.value(), context);
                if (call == null || !call.constructor()) {
                    return null;
                }
                return new ResolvedTarget(call.moduleName(), firstSegment(call.methodPath()), true, false);
            }

            ResolvedTarget resolveSymbol(String expression) {
                if (expression == null || expression.isBlank()) {
                    return null;
                }
                List<String> parts = parts(expression);
                if (parts.isEmpty()) {
                    return null;
                }

                ModulePrefix modulePrefix = symbolIndex.longestModulePrefix(parts);
                if (modulePrefix != null) {
                    List<String> rest = parts.subList(modulePrefix.partCount(), parts.size());
                    if (rest.isEmpty()) {
                        return new ResolvedTarget(modulePrefix.moduleName(), null, false, false);
                    }
                    Symbol symbol = symbolIndex.symbolInModule(modulePrefix.moduleName(), rest.get(0));
                    if (symbol != null) {
                        return new ResolvedTarget(symbol.moduleName(), join(rest), symbol.classSymbol(), symbol.functionSymbol());
                    }
                    return new ResolvedTarget(modulePrefix.moduleName(), join(rest), false, false);
                }

                AliasTarget alias = aliases.get(parts.get(0));
                if (alias != null) {
                    String memberPath = append(alias.memberPath(), parts.subList(1, parts.size()));
                    return new ResolvedTarget(alias.moduleName(), memberPath,
                            alias.classSymbol(), alias.functionSymbol());
                }

                Symbol local = symbolIndex.symbolInModule(module.moduleName(), parts.get(0));
                if (local != null) {
                    return new ResolvedTarget(local.moduleName(), join(parts), local.classSymbol(), local.functionSymbol());
                }
                return null;
            }

            ResolvedCall resolveCall(String expression, ScopeContext scopeContext) {
                if (expression == null || expression.isBlank()) {
                    return null;
                }
                List<String> parts = parts(expression);
                if (parts.isEmpty()) {
                    return null;
                }

                ResolvedCall variableCall = resolveVariableCall(parts, scopeContext);
                if (variableCall != null) {
                    return variableCall;
                }

                ResolvedTarget target = resolveSymbol(expression);
                if (target == null) {
                    return null;
                }
                String methodPath = target.memberPath();
                if (methodPath == null || methodPath.isBlank()) {
                    return null;
                }

                String first = firstSegment(methodPath);
                if (target.classSymbol()
                        || symbolIndex.isClass(target.moduleName(), first)) {
                    if (methodPath.equals(first)) {
                        return new ResolvedCall(target.moduleName(), first + ".__init__",
                                EdgeKind.INSTANTIATES, true);
                    }
                    return new ResolvedCall(target.moduleName(), methodPath, EdgeKind.CALLS, false);
                }
                return new ResolvedCall(target.moduleName(), methodPath, EdgeKind.CALLS, false);
            }

            private ResolvedCall resolveVariableCall(List<String> parts, ScopeContext scopeContext) {
                if (parts.size() < 2) {
                    return null;
                }

                if ("self".equals(parts.get(0)) && parts.size() >= 3 && scopeContext.scope().className() != null) {
                    Map<String, ResolvedTarget> fields = classFieldTypes.getOrDefault(
                            scopeContext.scope().className(), Map.of());
                    ResolvedTarget fieldType = fields.get(parts.get(1));
                    if (fieldType != null) {
                        String methodPath = fieldType.memberPath() + "." + join(parts.subList(2, parts.size()));
                        return new ResolvedCall(fieldType.moduleName(), methodPath, EdgeKind.CALLS, false);
                    }
                }

                ResolvedTarget localType = scopeContext.localTypes().get(parts.get(0));
                if (localType != null) {
                    String methodPath = localType.memberPath() + "." + join(parts.subList(1, parts.size()));
                    return new ResolvedCall(localType.moduleName(), methodPath, EdgeKind.CALLS, false);
                }
                return null;
            }
        }

        private record ScopeContext(ParsedPythonModule.ScopeDef scope,
                                    Map<String, ResolvedTarget> localTypes) {
        }
    }

    private static final class SymbolIndex {
        private final Set<String> modules = new LinkedHashSet<>();
        private final Map<String, String> importableModules = new LinkedHashMap<>();
        private final Map<String, Map<String, Symbol>> symbolsByModule = new LinkedHashMap<>();

        SymbolIndex(Collection<ParsedPythonModule> parsedModules) {
            for (ParsedPythonModule module : parsedModules) {
                modules.add(module.moduleName());
                importableModules.put(module.moduleName(), module.moduleName());
                if (module.moduleName().endsWith(".__init__")) {
                    String packageName = module.moduleName()
                            .substring(0, module.moduleName().length() - ".__init__".length());
                    if (!packageName.isBlank()) {
                        importableModules.put(packageName, module.moduleName());
                    }
                }
            }
            for (ParsedPythonModule module : parsedModules) {
                Map<String, Symbol> symbols = symbolsByModule.computeIfAbsent(
                        module.moduleName(), ignored -> new LinkedHashMap<>());
                for (ParsedPythonModule.ClassDef cls : module.classes()) {
                    symbols.put(cls.name(), new Symbol(module.moduleName(), cls.name(), true, false));
                }
                for (ParsedPythonModule.ScopeDef scope : module.scopes()) {
                    if ("function".equals(scope.kind()) || "async_function".equals(scope.kind())) {
                        if (scope.className() == null && !scope.name().contains(".")) {
                            symbols.put(scope.name(), new Symbol(module.moduleName(), scope.name(), false, true));
                        }
                    }
                }
            }
        }

        boolean isModule(String moduleName) {
            return resolveModule(moduleName) != null;
        }

        String resolveModule(String moduleName) {
            return moduleName == null ? null : importableModules.get(moduleName);
        }

        boolean isClass(String moduleName, String name) {
            Symbol symbol = symbolInModule(moduleName, name);
            return symbol != null && symbol.classSymbol();
        }

        Symbol symbolInModule(String moduleName, String name) {
            if (moduleName == null || name == null) {
                return null;
            }
            return symbolsByModule.getOrDefault(moduleName, Map.of()).get(name);
        }

        ModulePrefix longestModulePrefix(List<String> parts) {
            for (int i = parts.size(); i >= 1; i--) {
                String candidate = join(parts.subList(0, i));
                String resolved = resolveModule(candidate);
                if (resolved != null) {
                    return new ModulePrefix(resolved, i);
                }
            }
            return null;
        }
    }

    private record Symbol(String moduleName, String memberPath, boolean classSymbol, boolean functionSymbol) {
    }

    private record ModulePrefix(String moduleName, int partCount) {
    }

    private record AliasTarget(String moduleName, String memberPath, boolean classSymbol, boolean functionSymbol) {
        static AliasTarget module(String moduleName) {
            return new AliasTarget(moduleName, null, false, false);
        }

        static AliasTarget moduleMember(String moduleName, String memberPath) {
            return new AliasTarget(moduleName, memberPath, false, false);
        }

        static AliasTarget symbol(Symbol symbol) {
            return new AliasTarget(symbol.moduleName(), symbol.memberPath(),
                    symbol.classSymbol(), symbol.functionSymbol());
        }
    }

    private record ResolvedTarget(String moduleName, String memberPath, boolean classSymbol, boolean functionSymbol) {
    }

    private record ResolvedCall(String moduleName, String methodPath, EdgeKind kind, boolean constructor) {
    }

    private static List<String> parts(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : expression.split("\\.")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String join(Collection<String> parts) {
        return String.join(".", parts);
    }

    private static String append(String prefix, List<String> suffixParts) {
        if (suffixParts == null || suffixParts.isEmpty()) {
            return prefix;
        }
        String suffix = join(suffixParts);
        return prefix == null || prefix.isBlank() ? suffix : prefix + "." + suffix;
    }

    private static String firstSegment(String value) {
        if (value == null) {
            return null;
        }
        int dot = value.indexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }
}
