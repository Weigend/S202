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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process parser for ES modules — the JS counterpart of
 * {@code ExternalPythonAstProvider}, but without shelling out: JavaScript's
 * static {@code import}/{@code export} syntax is regular enough that a
 * comment- and string-aware scan yields the module dependency graph, class
 * hierarchy and top-level declarations directly.
 *
 * <p>Only static structure is extracted (imports, exports, classes,
 * top-level functions, and value uses of imported bindings). Deep, value-flow
 * call resolution is intentionally omitted — in a dynamically typed language
 * it is low-value and error-prone; the meaningful signal is the module graph.</p>
 */
public final class JavaScriptParser implements JavaScriptAstProvider {

    // import <clause> from '<source>'   |   import '<source>' (side-effect)
    private static final Pattern IMPORT_FROM = Pattern.compile(
            "\\bimport\\s+([^;'\"]*?)\\s+from\\s+(['\"])(.*?)\\2");
    private static final Pattern IMPORT_SIDE_EFFECT = Pattern.compile(
            "\\bimport\\s+(['\"])(.*?)\\1");
    private static final Pattern IMPORT_DYNAMIC = Pattern.compile(
            "\\bimport\\s*\\(\\s*(['\"])(.*?)\\1");
    // export { … } from '<source>'   |   export * from '<source>'
    private static final Pattern EXPORT_FROM = Pattern.compile(
            "\\bexport\\s+(?:\\*(?:\\s+as\\s+[\\w$]+)?|\\{[^}]*})\\s+from\\s+(['\"])(.*?)\\1");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\bclass\\s+([A-Za-z_$][\\w$]*)\\s*(?:extends\\s+([A-Za-z_$][\\w$.]*))?\\s*\\{");
    private static final Pattern FUNCTION_DECL = Pattern.compile(
            "\\bfunction\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\(");
    // A method signature inside a class body: optional modifiers, name, params, "{".
    private static final Pattern CLASS_METHOD = Pattern.compile(
            "(?m)^\\s*(?:static\\s+|async\\s+|get\\s+|set\\s+|\\*\\s*)*"
            + "([A-Za-z_$][\\w$]*)\\s*\\([^)]*\\)\\s*\\{");
    private static final java.util.Set<String> NON_METHOD_KEYWORDS = java.util.Set.of(
            "if", "for", "while", "switch", "catch", "return", "function", "do");
    // export const foo = ( … ) =>   |   export const foo = function
    private static final Pattern EXPORTED_CONST_FN = Pattern.compile(
            "\\bexport\\s+const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>"
            + "|\\bexport\\s+const\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s+)?function");
    // (new )?Head(.member)?(   — head/member captured, call/args ignored
    private static final Pattern USAGE = Pattern.compile(
            "(\\bnew\\s+)?\\b([A-Za-z_$][\\w$]*)\\s*(?:\\.\\s*([A-Za-z_$][\\w$]*))?\\s*\\(");

    @Override
    public List<ParsedJavaScriptModule> parse(List<ModuleSource> sources) throws IOException {
        List<ParsedJavaScriptModule> result = new ArrayList<>();
        if (sources == null) {
            return result;
        }
        for (ModuleSource source : sources) {
            String text = Files.readString(source.sourcePath(), StandardCharsets.UTF_8);
            result.add(parseModule(source.moduleName(), source.sourcePath().toString(), text));
        }
        return result;
    }

    ParsedJavaScriptModule parseModule(String moduleName, String sourcePath, String text) {
        String noComments = mask(text, false);   // comments → spaces, strings intact
        String noStrings = mask(text, true);      // comments AND strings → spaces

        List<ParsedJavaScriptModule.ImportRef> imports = new ArrayList<>();
        List<ParsedJavaScriptModule.ClassDef> classes = new ArrayList<>();
        List<ParsedJavaScriptModule.FunctionDef> functions = new ArrayList<>();
        List<ParsedJavaScriptModule.UsageRef> usages = new ArrayList<>();

        collectImports(noComments, imports);
        collectClasses(noComments, noStrings, classes, functions);
        collectFunctions(noComments, functions);
        collectUsages(noStrings, usages);

        return new ParsedJavaScriptModule(moduleName, sourcePath, imports, classes, functions, usages);
    }

    private void collectImports(String src, List<ParsedJavaScriptModule.ImportRef> out) {
        Matcher m = IMPORT_FROM.matcher(src);
        while (m.find()) {
            parseImportClause(m.group(1), m.group(3), lineAt(src, m.start()), out);
        }
        m = IMPORT_SIDE_EFFECT.matcher(src);
        while (m.find()) {
            // Skip matches that are really the "import <clause> from" form (handled above).
            String after = src.substring(m.end(), Math.min(src.length(), m.end() + 6));
            if (after.stripLeading().startsWith("from")) {
                continue;
            }
            out.add(new ParsedJavaScriptModule.ImportRef(
                    "side_effect", m.group(2), null, null, lineAt(src, m.start())));
        }
        m = IMPORT_DYNAMIC.matcher(src);
        while (m.find()) {
            out.add(new ParsedJavaScriptModule.ImportRef(
                    "dynamic", m.group(2), null, null, lineAt(src, m.start())));
        }
        m = EXPORT_FROM.matcher(src);
        while (m.find()) {
            out.add(new ParsedJavaScriptModule.ImportRef(
                    "reexport", m.group(2), null, null, lineAt(src, m.start())));
        }
    }

    private void parseImportClause(String clause, String source, int line,
                                   List<ParsedJavaScriptModule.ImportRef> out) {
        String rest = clause == null ? "" : clause.trim();

        int brace = rest.indexOf('{');
        String namedBlock = null;
        if (brace >= 0) {
            int close = rest.indexOf('}', brace);
            namedBlock = close > brace ? rest.substring(brace + 1, close) : rest.substring(brace + 1);
            rest = (rest.substring(0, brace) + (close > brace ? rest.substring(close + 1) : "")).trim();
        }

        // Leading "default" and/or "* as NS", comma-separated.
        for (String part : rest.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            Matcher ns = Pattern.compile("\\*\\s+as\\s+([A-Za-z_$][\\w$]*)").matcher(token);
            if (ns.find()) {
                out.add(new ParsedJavaScriptModule.ImportRef("namespace", source, null, ns.group(1), line));
            } else if (token.matches("[A-Za-z_$][\\w$]*")) {
                out.add(new ParsedJavaScriptModule.ImportRef("default", source, "default", token, line));
            }
        }

        if (namedBlock != null) {
            for (String spec : namedBlock.split(",")) {
                String token = spec.trim();
                if (token.isEmpty()) {
                    continue;
                }
                String[] asParts = token.split("\\s+as\\s+");
                String imported = asParts[0].trim();
                String local = asParts.length > 1 ? asParts[1].trim() : imported;
                if (!imported.isEmpty()) {
                    out.add(new ParsedJavaScriptModule.ImportRef("named", source, imported, local, line));
                }
            }
        }
    }

    private void collectClasses(String noComments, String noStrings,
                                List<ParsedJavaScriptModule.ClassDef> classes,
                                List<ParsedJavaScriptModule.FunctionDef> methods) {
        Matcher m = CLASS_DECL.matcher(noComments);
        while (m.find()) {
            String className = m.group(1);
            classes.add(new ParsedJavaScriptModule.ClassDef(className, m.group(2), lineAt(noComments, m.start())));
            // The class body spans from the opening "{" (last char of the match)
            // to its matching "}". Brace-match on the string-masked source so
            // braces inside string/template literals don't throw off the count.
            int open = m.end() - 1;
            int close = matchingBrace(noStrings, open);
            if (close < 0) {
                continue;
            }
            Matcher mm = CLASS_METHOD.matcher(noStrings).region(open + 1, close);
            while (mm.find()) {
                String name = mm.group(1);
                if (NON_METHOD_KEYWORDS.contains(name)) {
                    continue;
                }
                methods.add(new ParsedJavaScriptModule.FunctionDef(
                        className + "." + name, lineAt(noStrings, mm.start(1))));
            }
        }
    }

    /** Index of the "}" matching the "{" at {@code openBrace}, or -1. */
    private static int matchingBrace(String src, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void collectFunctions(String src, List<ParsedJavaScriptModule.FunctionDef> out) {
        Matcher m = FUNCTION_DECL.matcher(src);
        while (m.find()) {
            out.add(new ParsedJavaScriptModule.FunctionDef(m.group(1), lineAt(src, m.start())));
        }
        m = EXPORTED_CONST_FN.matcher(src);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            out.add(new ParsedJavaScriptModule.FunctionDef(name, lineAt(src, m.start())));
        }
    }

    private void collectUsages(String src, List<ParsedJavaScriptModule.UsageRef> out) {
        Matcher m = USAGE.matcher(src);
        while (m.find()) {
            boolean constructor = m.group(1) != null;
            String head = m.group(2);
            String member = m.group(3);
            out.add(new ParsedJavaScriptModule.UsageRef(head, constructor, member, lineAt(src, m.start())));
        }
    }

    /* ----- comment/string masking (a small state scanner) ------------------- */

    /**
     * Replaces comments (and, when {@code maskStrings} is set, string
     * literals) with spaces, preserving newlines so byte offsets and line
     * numbers stay aligned with the original.
     */
    static String mask(String s, boolean maskStrings) {
        char[] out = s.toCharArray();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    if (s.charAt(i) != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                    if (i < n) {
                        out[i++] = ' ';
                    }
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;                                   // keep the opening quote
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        if (maskStrings) {
                            out[i] = ' ';
                            if (i + 1 < n && s.charAt(i + 1) != '\n') {
                                out[i + 1] = ' ';
                            }
                        }
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        i++;                            // keep the closing quote
                        break;
                    }
                    if (maskStrings && d != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int lineAt(String src, int index) {
        int line = 1;
        int limit = Math.min(index, src.length());
        for (int i = 0; i < limit; i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
