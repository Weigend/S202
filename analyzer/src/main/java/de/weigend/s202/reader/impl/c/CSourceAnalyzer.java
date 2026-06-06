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
package de.weigend.s202.reader.impl.c;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PoC analyzer for C source trees. It maps C translation units to S202 classes,
 * C functions to methods, project includes to IMPORTS edges, and direct
 * function calls to CALLS edges.
 */
@Singleton
public class CSourceAnalyzer implements LanguageAnalyzer {

    private static final Set<String> EXCLUDED_DIR_NAMES = Set.of(
            ".git", ".hg", ".svn",
            ".idea", ".vscode",
            "bin", "build", "cmake-build-debug", "cmake-build-release",
            "doc", "docs", "exe", "lib", "target");

    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "asm", "case", "catch", "do", "else", "for", "if", "return",
            "sizeof", "switch", "try", "while");

    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "^\\s*#\\s*include\\s*([<\"])([^>\"]+)[>\"]");

    private static final Pattern CALL_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    @Override
    public String displayName() {
        return "C";
    }

    public DependencyModel analyze(Path sourceRootOrProjectRoot) throws IOException {
        return analyze(List.of(sourceRootOrProjectRoot));
    }

    @Override
    public DependencyModel analyze(List<Path> roots) throws IOException {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("at least one C source root is required");
        }

        List<SourceRoot> sourceRoots = discoverSourceRoots(roots);
        List<ModuleFile> modules = discoverModules(sourceRoots);
        List<HeaderFile> headers = discoverHeaders(sourceRoots);

        DependencyModel model = new DependencyModel();
        for (ModuleFile module : modules) {
            model.addClass(module.className(), new DependencyModel.ClassInfo(
                    module.className(), module.simpleName(), module.packageName(), false));
        }

        List<ParsedCFile> parsedFiles = new ArrayList<>();
        for (ModuleFile module : modules) {
            parsedFiles.add(parseCFile(module));
        }

        List<ParsedHeader> parsedHeaders = new ArrayList<>();
        for (HeaderFile header : headers) {
            parsedHeaders.add(parseHeader(header));
        }

        CDependencyResolver resolver = new CDependencyResolver(model, parsedFiles, parsedHeaders);
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
            if (Files.isRegularFile(normalized) && isCSource(normalized)) {
                Path sourceRoot = normalized.getParent();
                Path projectRoot = sourceRoot;
                result.add(new SourceRoot(sourceRoot, projectRoot, includeRoots(projectRoot, sourceRoot)));
                continue;
            }
            if (!Files.isDirectory(normalized)) {
                throw new IOException("C source root does not exist or is not a directory: " + normalized);
            }

            Path src = normalized.resolve("src");
            if (Files.isDirectory(src) && containsCSourceFile(src)) {
                result.add(new SourceRoot(src.toAbsolutePath().normalize(),
                        normalized, includeRoots(normalized, src)));
                continue;
            }

            Path projectRoot = "src".equals(normalized.getFileName().toString()) && normalized.getParent() != null
                    ? normalized.getParent()
                    : normalized;
            result.add(new SourceRoot(normalized, projectRoot, includeRoots(projectRoot, normalized)));
        }
        return result.stream().toList();
    }

    private static List<Path> includeRoots(Path projectRoot, Path sourceRoot) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        Path include = projectRoot.resolve("include").toAbsolutePath().normalize();
        if (Files.isDirectory(include)) {
            roots.add(include);
        }
        roots.add(sourceRoot.toAbsolutePath().normalize());
        return roots.stream().toList();
    }

    private static boolean containsCSourceFile(Path root) throws IOException {
        return !collectFiles(root, CSourceAnalyzer::isCSource).isEmpty();
    }

    private static List<ModuleFile> discoverModules(List<SourceRoot> sourceRoots) throws IOException {
        List<ModuleFile> modules = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            String syntheticRootPackage = syntheticRootPackage(sourceRoot.sourceRoot(), sourceRoot.projectRoot());
            for (Path file : collectFiles(sourceRoot.sourceRoot(), CSourceAnalyzer::isCSource)) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!seen.add(normalized)) {
                    continue;
                }
                modules.add(toModuleFile(sourceRoot.sourceRoot(), normalized, syntheticRootPackage));
            }
        }
        modules.sort(Comparator.comparing(ModuleFile::className));
        return modules;
    }

    private static List<HeaderFile> discoverHeaders(List<SourceRoot> sourceRoots) throws IOException {
        List<HeaderFile> headers = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            for (Path includeRoot : sourceRoot.includeRoots()) {
                if (!Files.isDirectory(includeRoot)) {
                    continue;
                }
                for (Path file : collectFiles(includeRoot, CSourceAnalyzer::isHeader)) {
                    Path normalized = file.toAbsolutePath().normalize();
                    if (!seen.add(normalized)) {
                        continue;
                    }
                    headers.add(new HeaderFile(normalized, includeRoot));
                }
            }
        }
        headers.sort(Comparator.comparing(HeaderFile::path));
        return headers;
    }

    private static List<Path> collectFiles(Path root, FilePredicate predicate) throws IOException {
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
                if (predicate.matches(file)) {
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
        String simpleName = fileName.substring(0, fileName.length() - ".c".length());

        Path parent = relative.getParent();
        String packageName = parent == null ? syntheticRootPackage : dottedPath(parent);
        String className = packageName + "." + sanitizeIdentifier(simpleName);
        return new ModuleFile(className, packageName, sanitizeIdentifier(simpleName), file);
    }

    private static ParsedCFile parseCFile(ModuleFile module) throws IOException {
        String source = readSource(module.path());
        String cleaned = stripCommentsStringsAndPreprocessor(source);
        List<FunctionDef> functions = parseFunctionDefinitions(cleaned, module.className());
        List<IncludeRef> includes = parseIncludes(source);
        return new ParsedCFile(module, includes, functions);
    }

    private static ParsedHeader parseHeader(HeaderFile header) throws IOException {
        String source = readSource(header.path());
        String cleaned = stripCommentsStringsAndPreprocessor(source);
        List<FunctionDecl> declarations = parseFunctionDeclarations(cleaned);
        List<IncludeName> includeNames = includeNames(header);
        return new ParsedHeader(header.path(), includeNames, declarations);
    }

    private static String readSource(Path path) throws IOException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException ignored) {
            return Files.readString(path, StandardCharsets.ISO_8859_1);
        }
    }

    private static List<IncludeRef> parseIncludes(String source) {
        List<IncludeRef> includes = new ArrayList<>();
        String[] lines = source.split("\\R");
        for (String line : lines) {
            Matcher matcher = INCLUDE_PATTERN.matcher(line);
            if (matcher.find()) {
                boolean system = "<".equals(matcher.group(1));
                includes.add(new IncludeRef(matcher.group(2).trim(), system));
            }
        }
        return includes;
    }

    private static List<FunctionDef> parseFunctionDefinitions(String cleaned, String className) {
        List<FunctionDef> functions = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    Signature signature = signatureBefore(cleaned, i);
                    int close = findMatchingForward(cleaned, i, '{', '}');
                    if (signature != null && close > i) {
                        String body = cleaned.substring(i + 1, close);
                        functions.add(new FunctionDef(className, signature.name(),
                                signature.descriptor(), signature.staticFunction(), parseCalls(body)));
                        i = close;
                        continue;
                    }
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
        }
        return functions;
    }

    private static List<FunctionDecl> parseFunctionDeclarations(String cleaned) {
        List<FunctionDecl> declarations = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{' || c == '(' || c == '[') {
                depth++;
            } else if ((c == '}' || c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == ';' && depth == 0) {
                Signature signature = signatureBefore(cleaned, i);
                if (signature != null && !signature.declarationPrefix().contains("typedef")) {
                    declarations.add(new FunctionDecl(signature.name(), signature.descriptor()));
                }
            }
        }
        return declarations;
    }

    private static List<String> parseCalls(String body) {
        List<String> calls = new ArrayList<>();
        Matcher matcher = CALL_PATTERN.matcher(body);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (CONTROL_KEYWORDS.contains(name)) {
                continue;
            }
            int previous = previousNonWhitespace(body, matcher.start() - 1);
            if (previous >= 0) {
                char c = body.charAt(previous);
                if (c == '.' || c == '>') {
                    continue;
                }
            }
            calls.add(name);
        }
        return calls;
    }

    private static Signature signatureBefore(String text, int boundary) {
        int closeParen = previousNonWhitespace(text, boundary - 1);
        if (closeParen < 0 || text.charAt(closeParen) != ')') {
            return null;
        }
        int openParen = findMatchingBackward(text, closeParen, '(', ')');
        if (openParen < 0) {
            return null;
        }
        Identifier identifier = identifierBefore(text, openParen - 1);
        if (identifier == null || CONTROL_KEYWORDS.contains(identifier.name())) {
            return null;
        }

        int prefixStart = declarationStart(text, identifier.start());
        String prefix = text.substring(prefixStart, identifier.start()).trim();
        if (prefix.isBlank() || prefix.contains("=") || prefix.contains(",")) {
            return null;
        }
        if (prefix.endsWith("(") || prefix.endsWith(".")) {
            return null;
        }

        String params = text.substring(openParen + 1, closeParen);
        return new Signature(identifier.name(), descriptor(params),
                Pattern.compile("(^|\\s)static(\\s|$)").matcher(prefix).find(), prefix);
    }

    private static int declarationStart(String text, int beforeIdentifier) {
        for (int i = beforeIdentifier - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ';' || c == '{' || c == '}') {
                return i + 1;
            }
        }
        return Math.max(0, beforeIdentifier - 200);
    }

    private static Identifier identifierBefore(String text, int position) {
        int end = previousNonWhitespace(text, position);
        if (end < 0 || !isIdentifierPart(text.charAt(end))) {
            return null;
        }
        int start = end;
        while (start >= 0 && isIdentifierPart(text.charAt(start))) {
            start--;
        }
        start++;
        if (start > end || !isIdentifierStart(text.charAt(start))) {
            return null;
        }
        return new Identifier(text.substring(start, end + 1), start, end + 1);
    }

    private static int previousNonWhitespace(String text, int from) {
        for (int i = from; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBackward(String text, int closeIndex, char open, char close) {
        int depth = 0;
        for (int i = closeIndex; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == close) {
                depth++;
            } else if (c == open) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingForward(String text, int openIndex, char open, char close) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripCommentsStringsAndPreprocessor(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineStart = true;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);

            if (lineStart && c == '#') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append('\n');
                }
                lineStart = true;
                continue;
            }

            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                out.append(' ');
                out.append(' ');
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append('\n');
                }
                lineStart = true;
                continue;
            }

            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                out.append(' ');
                out.append(' ');
                i += 2;
                while (i < source.length()) {
                    char comment = source.charAt(i);
                    if (comment == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                        out.append(' ');
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(comment == '\n' ? '\n' : ' ');
                    i++;
                }
                lineStart = false;
                continue;
            }

            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < source.length()) {
                    char stringChar = source.charAt(i);
                    if (stringChar == '\\' && i + 1 < source.length()) {
                        out.append(' ');
                        out.append(' ');
                        i += 2;
                        continue;
                    }
                    out.append(stringChar == '\n' ? '\n' : ' ');
                    if (stringChar == quote) {
                        break;
                    }
                    i++;
                }
                lineStart = false;
                continue;
            }

            out.append(c);
            if (c == '\n') {
                lineStart = true;
            } else if (!Character.isWhitespace(c)) {
                lineStart = false;
            }
        }
        return out.toString();
    }

    private static String descriptor(String rawParams) {
        String normalized = rawParams == null ? "" : rawParams
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s*\\*\\s*", " * ")
                .trim();
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank() || "void".equals(normalized)) {
            return "()";
        }
        return "(" + normalized + ")";
    }

    private static List<IncludeName> includeNames(HeaderFile header) {
        List<IncludeName> names = new ArrayList<>();
        Path relative = header.includeRoot().toAbsolutePath().normalize()
                .relativize(header.path().toAbsolutePath().normalize());
        names.add(new IncludeName(relative.toString().replace('\\', '/')));
        names.add(new IncludeName(header.path().getFileName().toString()));
        return names.stream().distinct().toList();
    }

    private static boolean isCSource(Path path) {
        return path.getFileName().toString().endsWith(".c");
    }

    private static boolean isHeader(Path path) {
        return path.getFileName().toString().endsWith(".h");
    }

    private static String dottedPath(Path path) {
        List<String> parts = new ArrayList<>();
        for (Path part : path) {
            parts.add(sanitizeIdentifier(part.toString()));
        }
        return String.join(".", parts);
    }

    private static String syntheticRootPackage(Path sourceRoot, Path projectRoot) {
        Path sourceName = sourceRoot.getFileName();
        if (sourceName != null && !"src".equalsIgnoreCase(sourceName.toString())) {
            return sanitizeIdentifier(sourceName.toString());
        }
        Path projectName = projectRoot.getFileName();
        if (projectName != null) {
            return sanitizeIdentifier(projectName.toString());
        }
        return "c";
    }

    private static String sanitizeIdentifier(String raw) {
        String normalized = raw == null || raw.isBlank() ? "c" : raw;
        normalized = normalized.replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isBlank()) {
            normalized = "c";
        }
        if (!Character.isJavaIdentifierStart(normalized.charAt(0))) {
            normalized = "pkg_" + normalized;
        }
        return normalized;
    }

    private static String basenameWithoutExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    @FunctionalInterface
    private interface FilePredicate {
        boolean matches(Path file);
    }

    private record SourceRoot(Path sourceRoot, Path projectRoot, List<Path> includeRoots) {
        private SourceRoot {
            sourceRoot = sourceRoot.toAbsolutePath().normalize();
            projectRoot = projectRoot.toAbsolutePath().normalize();
            includeRoots = includeRoots.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        }
    }

    private record ModuleFile(String className, String packageName, String simpleName, Path path) {
    }

    private record HeaderFile(Path path, Path includeRoot) {
    }

    private record IncludeRef(String name, boolean system) {
    }

    private record IncludeName(String name) {
    }

    private record ParsedCFile(ModuleFile module, List<IncludeRef> includes, List<FunctionDef> functions) {
    }

    private record ParsedHeader(Path path, List<IncludeName> includeNames, List<FunctionDecl> declarations) {
    }

    private record FunctionDef(String className, String name, String descriptor,
                               boolean staticFunction, List<String> calls) {
    }

    private record FunctionDecl(String name, String descriptor) {
    }

    private record Signature(String name, String descriptor, boolean staticFunction,
                             String declarationPrefix) {
    }

    private record Identifier(String name, int start, int end) {
    }

    private record ResolvedFunction(String className, String methodName, String descriptor) {
    }

    private static final class CDependencyResolver {
        private final DependencyModel model;
        private final List<ParsedCFile> files;
        private final List<ParsedHeader> headers;
        private final Map<String, ParsedHeader> headersByIncludeName = new LinkedHashMap<>();
        private final Map<Path, String> headerOwners = new LinkedHashMap<>();
        private final Map<String, List<FunctionDef>> definitionsByName = new LinkedHashMap<>();
        private final Map<String, Map<String, FunctionDef>> definitionsByClass = new LinkedHashMap<>();
        private final Map<String, Map<String, Set<String>>> visibleOwnersByClass = new LinkedHashMap<>();

        CDependencyResolver(DependencyModel model, List<ParsedCFile> files, List<ParsedHeader> headers) {
            this.model = Objects.requireNonNull(model, "model");
            this.files = files == null ? List.of() : files;
            this.headers = headers == null ? List.of() : headers;
        }

        void populate() {
            indexFunctions();
            indexHeadersByIncludeName();
            resolveHeaderOwners();
            addDeclaredMethods();
            buildVisibleOwnerMaps();
            addIncludeDependencies();
            addCallDependencies();
        }

        private void indexFunctions() {
            for (ParsedCFile file : files) {
                for (FunctionDef function : file.functions()) {
                    definitionsByName.computeIfAbsent(function.name(), ignored -> new ArrayList<>())
                            .add(function);
                    definitionsByClass.computeIfAbsent(function.className(), ignored -> new LinkedHashMap<>())
                            .putIfAbsent(function.name(), function);
                }
            }
        }

        private void indexHeadersByIncludeName() {
            Map<String, List<ParsedHeader>> candidates = new LinkedHashMap<>();
            for (ParsedHeader header : headers) {
                for (IncludeName includeName : header.includeNames()) {
                    candidates.computeIfAbsent(includeName.name(), ignored -> new ArrayList<>()).add(header);
                }
            }
            for (Map.Entry<String, List<ParsedHeader>> entry : candidates.entrySet()) {
                if (entry.getValue().size() == 1) {
                    headersByIncludeName.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        }

        private void resolveHeaderOwners() {
            Map<String, List<String>> classesBySimpleName = new LinkedHashMap<>();
            for (ParsedCFile file : files) {
                classesBySimpleName.computeIfAbsent(file.module().simpleName(), ignored -> new ArrayList<>())
                        .add(file.module().className());
            }

            for (ParsedHeader header : headers) {
                String baseName = sanitizeIdentifier(basenameWithoutExtension(header.path()));
                String owner = unique(classesBySimpleName.get(baseName));
                if (owner == null) {
                    owner = ownerFromDeclarations(header);
                }
                if (owner == null) {
                    owner = ownerFromPrefix(header, classesBySimpleName);
                }
                if (owner != null) {
                    headerOwners.put(header.path(), owner);
                }
            }
        }

        private String ownerFromDeclarations(ParsedHeader header) {
            Map<String, Integer> scoreByClass = new LinkedHashMap<>();
            for (FunctionDecl declaration : header.declarations()) {
                for (FunctionDef definition : definitionsByName.getOrDefault(declaration.name(), List.of())) {
                    if (definition.staticFunction()) {
                        continue;
                    }
                    scoreByClass.merge(definition.className(), 1, Integer::sum);
                }
            }
            return highestUniqueScore(scoreByClass);
        }

        private String ownerFromPrefix(ParsedHeader header, Map<String, List<String>> classesBySimpleName) {
            Map<String, Integer> prefixScores = new LinkedHashMap<>();
            for (FunctionDecl declaration : header.declarations()) {
                int underscore = declaration.name().indexOf('_');
                if (underscore <= 0) {
                    continue;
                }
                String prefix = sanitizeIdentifier(declaration.name().substring(0, underscore));
                for (String className : classesBySimpleName.getOrDefault(prefix, List.of())) {
                    prefixScores.merge(className, 1, Integer::sum);
                }
            }
            return highestUniqueScore(prefixScores);
        }

        private void addDeclaredMethods() {
            for (ParsedCFile file : files) {
                DependencyModel.ClassInfo classInfo = model.getClass(file.module().className());
                if (classInfo == null) {
                    continue;
                }
                for (FunctionDef function : file.functions()) {
                    classInfo.addMethod(function.name(), function.descriptor());
                }
            }
        }

        private void buildVisibleOwnerMaps() {
            for (ParsedCFile file : files) {
                Map<String, Set<String>> visible = new LinkedHashMap<>();
                for (IncludeRef include : file.includes()) {
                    ParsedHeader header = resolveHeader(include);
                    if (header == null) {
                        continue;
                    }
                    String owner = headerOwners.get(header.path());
                    if (owner == null) {
                        continue;
                    }
                    for (FunctionDecl declaration : header.declarations()) {
                        visible.computeIfAbsent(declaration.name(), ignored -> new LinkedHashSet<>()).add(owner);
                    }
                }
                visibleOwnersByClass.put(file.module().className(), visible);
            }
        }

        private void addIncludeDependencies() {
            for (ParsedCFile file : files) {
                DependencyModel.ClassInfo classInfo = model.getClass(file.module().className());
                if (classInfo == null) {
                    continue;
                }
                for (IncludeRef include : file.includes()) {
                    if (include.system()) {
                        continue;
                    }
                    ParsedHeader header = resolveHeader(include);
                    if (header == null) {
                        continue;
                    }
                    addDependency(classInfo, headerOwners.get(header.path()), EdgeKind.IMPORTS);
                }
            }
        }

        private void addCallDependencies() {
            for (ParsedCFile file : files) {
                DependencyModel.ClassInfo classInfo = model.getClass(file.module().className());
                if (classInfo == null) {
                    continue;
                }
                for (FunctionDef function : file.functions()) {
                    DependencyModel.MethodInfo methodInfo = classInfo.getMethod(
                            function.name(), function.descriptor());
                    if (methodInfo == null) {
                        continue;
                    }
                    for (String callName : function.calls()) {
                        ResolvedFunction target = resolveCall(file.module().className(), callName);
                        if (target == null || target.className().equals(file.module().className())) {
                            continue;
                        }
                        if (addDependency(classInfo, target.className(), EdgeKind.CALLS)) {
                            String methodCall = target.className() + "." + target.methodName();
                            methodInfo.methodCalls.merge(methodCall, 1, Integer::sum);
                            methodInfo.methodCallDescriptors
                                    .computeIfAbsent(methodCall, ignored -> new LinkedHashSet<>())
                                    .add(target.descriptor());
                        }
                    }
                }
            }
        }

        private ResolvedFunction resolveCall(String sourceClass, String callName) {
            FunctionDef local = definitionsByClass.getOrDefault(sourceClass, Map.of()).get(callName);
            if (local != null) {
                return resolved(local);
            }

            Set<String> visibleOwners = visibleOwnersByClass
                    .getOrDefault(sourceClass, Map.of())
                    .getOrDefault(callName, Set.of());
            String visibleOwner = unique(visibleOwners);
            if (visibleOwner != null) {
                FunctionDef target = definitionsByClass.getOrDefault(visibleOwner, Map.of()).get(callName);
                return target == null
                        ? new ResolvedFunction(visibleOwner, callName, "()")
                        : resolved(target);
            }

            List<FunctionDef> definitions = definitionsByName.getOrDefault(callName, List.of());
            FunctionDef uniqueGlobal = uniqueFunction(definitions.stream()
                    .filter(function -> !function.staticFunction())
                    .toList());
            return uniqueGlobal == null ? null : resolved(uniqueGlobal);
        }

        private ParsedHeader resolveHeader(IncludeRef include) {
            ParsedHeader header = headersByIncludeName.get(include.name());
            if (header != null) {
                return header;
            }
            int slash = Math.max(include.name().lastIndexOf('/'), include.name().lastIndexOf('\\'));
            if (slash >= 0) {
                return headersByIncludeName.get(include.name().substring(slash + 1));
            }
            return null;
        }

        private boolean addDependency(DependencyModel.ClassInfo from, String to, EdgeKind kind) {
            if (from == null || to == null || to.isBlank() || from.fullName.equals(to)) {
                return false;
            }
            if (model.getClass(to) == null) {
                return false;
            }
            from.addDependency(to, kind);
            return true;
        }

        private static ResolvedFunction resolved(FunctionDef function) {
            return new ResolvedFunction(function.className(), function.name(), function.descriptor());
        }

        private static String highestUniqueScore(Map<String, Integer> scoreByClass) {
            int best = 0;
            String owner = null;
            boolean ambiguous = false;
            for (Map.Entry<String, Integer> entry : scoreByClass.entrySet()) {
                int score = entry.getValue();
                if (score > best) {
                    best = score;
                    owner = entry.getKey();
                    ambiguous = false;
                } else if (score == best) {
                    ambiguous = true;
                }
            }
            return best > 0 && !ambiguous ? owner : null;
        }

        private static String unique(Collection<String> values) {
            if (values == null || values.size() != 1) {
                return null;
            }
            return values.iterator().next();
        }

        private static FunctionDef uniqueFunction(List<FunctionDef> functions) {
            return functions.size() == 1 ? functions.get(0) : null;
        }
    }
}
