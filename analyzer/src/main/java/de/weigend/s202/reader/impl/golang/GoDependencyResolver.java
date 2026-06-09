package de.weigend.s202.reader.impl.golang;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;

import java.util.*;
import java.util.ArrayList;

/**
 * Resolves a list of {@link ParsedGoFile}s into a {@link DependencyModel}.
 *
 * <h2>Two-pass algorithm per file</h2>
 * <ol>
 *   <li>Pass 1 — build the local type index: collect all type names defined in
 *       the file; create Typ-ClassInfos for exported types.</li>
 *   <li>Pass 2 — assign free functions: determine the dominant type of each
 *       free function (return type wins over first parameter, after unwrapping
 *       and filtering stdlib/qualified types); if a local type matches, the
 *       function becomes a MethodInfo of that type's ClassInfo; otherwise it
 *       goes to the file's DefaultClass.</li>
 * </ol>
 * Package-level variables and {@code init()} always go to the
 * DefaultPackageClass (one per package). When a file's base name equals the
 * package name, DefaultClass and DefaultPackageClass merge into one node.
 */
public class GoDependencyResolver {

    private static final Set<String> STDLIB_PRIMITIVES = Set.of(
            "error", "bool", "string", "int", "int8", "int16", "int32", "int64",
            "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
            "float32", "float64", "complex64", "complex128",
            "byte", "rune", "any", "comparable");

    private final String moduleName;
    private final List<ParsedGoFile> files;

    public GoDependencyResolver(String moduleName, List<ParsedGoFile> files) {
        this.moduleName = Objects.requireNonNull(moduleName);
        this.files = List.copyOf(files);
    }

    public DependencyModel resolve() {
        DependencyModel model = new DependencyModel();

        // ── Pass 1: type index + Typ-ClassInfos ──────────────────────────────
        // fileLocalTypes: file → (typeName → classInfoFQN or null for unexported)
        Map<ParsedGoFile, Map<String, String>> fileLocalTypes = new LinkedHashMap<>();

        for (ParsedGoFile file : files) {
            String pkgFQN = packageFQN(file.importPath());
            Map<String, String> localTypes = new LinkedHashMap<>();
            for (ParsedGoFile.TypeDecl type : file.types()) {
                if (isExported(type.name())) {
                    String fqn = pkgFQN + "." + type.name();
                    model.addClass(fqn, new DependencyModel.ClassInfo(
                            fqn, type.name(), pkgFQN,
                            "interface".equals(type.kind())));
                    localTypes.put(type.name(), fqn);
                } else {
                    localTypes.put(type.name(), null); // tracked but no ClassInfo
                }
            }
            fileLocalTypes.put(file, localTypes);
        }

        // ── Pass 2: assign functions/vars; build function-owner index ─────────
        // functionOwnerIndex: "pkgFQN.symbolName" → ClassInfo fullName
        Map<String, String> functionOwnerIndex = new LinkedHashMap<>();

        for (ParsedGoFile file : files) {
            String pkgFQN   = packageFQN(file.importPath());
            Map<String, String> localTypes = fileLocalTypes.get(file);
            String baseName = fileBaseName(file.filePath());
            boolean merge   = baseName.equals(file.packageName());

            String defaultClassFQN   = pkgFQN + "." + baseName;
            String defaultPkgFQN     = pkgFQN + "." + file.packageName();

            for (ParsedGoFile.FunctionDecl fn : file.functions()) {

                // Methods (have receiver) → receiver's Typ-ClassInfo
                if (fn.receiver() != null && !fn.receiver().isEmpty()) {
                    String ownerFQN = localTypes.getOrDefault(fn.receiver(), null);
                    if (ownerFQN == null) {
                        // unexported receiver — still register ClassInfo so methods land somewhere
                        ownerFQN = pkgFQN + "." + fn.receiver();
                        ensureClassInfo(model, ownerFQN, fn.receiver(), pkgFQN, false);
                    }
                    model.getClass(ownerFQN).addMethod(fn.name(), descriptor(fn.params()));
                    functionOwnerIndex.put(pkgFQN + "." + fn.receiver() + "." + fn.name(), ownerFQN);
                    functionOwnerIndex.put(pkgFQN + "." + fn.name(), ownerFQN); // also plain name for lookup
                    continue;
                }

                // init() and other free functions
                if ("init".equals(fn.name())) {
                    String dpFQN = merge ? defaultClassFQN : defaultPkgFQN;
                    ensureDefaultPackageClass(model, dpFQN, file.packageName(), pkgFQN);
                    model.getClass(dpFQN).addMethod(fn.name(), descriptor(fn.params()));
                    functionOwnerIndex.put(pkgFQN + ".init", dpFQN);
                    continue;
                }

                String dominant = dominantType(fn, localTypes);
                if (dominant != null) {
                    // assign to Typ-ClassInfo
                    String ownerFQN = localTypes.get(dominant);
                    model.getClass(ownerFQN).addMethod(fn.name(), descriptor(fn.params()));
                    functionOwnerIndex.put(pkgFQN + "." + fn.name(), ownerFQN);
                } else {
                    // DefaultClass
                    String dcFQN = merge ? defaultPkgFQN : defaultClassFQN;
                    ensureDefaultClass(model, dcFQN, baseName, pkgFQN);
                    model.getClass(dcFQN).addMethod(fn.name(), descriptor(fn.params()));
                    functionOwnerIndex.put(pkgFQN + "." + fn.name(), dcFQN);
                }
            }

            // Package-level vars → DefaultPackageClass
            if (!file.vars().isEmpty()) {
                String dpFQN = merge ? defaultClassFQN : defaultPkgFQN;
                ensureDefaultPackageClass(model, dpFQN, file.packageName(), pkgFQN);
            }
        }

        // ── Dependency extraction ─────────────────────────────────────────────
        for (ParsedGoFile file : files) {
            String pkgFQN = packageFQN(file.importPath());
            Map<String, String> localTypes = fileLocalTypes.get(file);

            for (ParsedGoFile.TypeDecl type : file.types()) {
                if (!isExported(type.name())) continue;
                String ownerFQN = pkgFQN + "." + type.name();
                DependencyModel.ClassInfo ownerClass = model.getClass(ownerFQN);
                if (ownerClass == null) continue;

                // Struct embedding → EXTENDS
                for (String embed : type.embeds()) {
                    resolveTypeRef(embed, pkgFQN, model, functionOwnerIndex)
                            .ifPresent(t -> ownerClass.addDependency(t, EdgeKind.EXTENDS));
                }

                // Field types → USES
                for (ParsedGoFile.FieldDecl field : type.fields()) {
                    resolveFieldRef(field, pkgFQN, model, functionOwnerIndex)
                            .ifPresent(t -> ownerClass.addDependency(t, EdgeKind.USES));
                }

                // Underlying type for type-definitions (e.g. type Book []Page → Book IMPORTS Page)
                // IMPORTS (not USES) so the LevelCalculator includes this structural dependency.
                if ("type".equals(type.kind()) && !type.baseType().isBlank()) {
                    resolveTypeRef(type.baseType(), pkgFQN, model, functionOwnerIndex)
                            .filter(t -> !t.equals(ownerFQN))
                            .ifPresent(t -> ownerClass.addDependency(t, EdgeKind.IMPORTS));
                }
            }

            // Function signature types → IMPORTS on the owning ClassInfo.
            // Using IMPORTS (not USES) so the LevelCalculator treats these as structural
            // dependencies. Within a package there are no explicit import statements, but
            // these type references are the architectural equivalent.
            for (ParsedGoFile.FunctionDecl fn : file.functions()) {
                String ownerFQN = resolveFunctionOwnerFQN(fn, pkgFQN, functionOwnerIndex);
                if (ownerFQN == null) continue;
                DependencyModel.ClassInfo ownerClass = model.getClass(ownerFQN);
                if (ownerClass == null) continue;

                List<String> sigTypes = new ArrayList<>();
                sigTypes.addAll(fn.params());
                sigTypes.addAll(fn.results());
                for (String typeRef : sigTypes) {
                    resolveTypeRef(typeRef, pkgFQN, model, functionOwnerIndex)
                            .filter(t -> !t.equals(ownerFQN))
                            .ifPresent(t -> ownerClass.addDependency(t, EdgeKind.IMPORTS));
                }
            }

            // Calls → CALLS / INSTANTIATES
            for (ParsedGoFile.CallRef call : file.calls()) {
                String callerFQN = resolveCallerFQN(call.callerFunction(), pkgFQN, functionOwnerIndex);
                if (callerFQN == null) continue;
                DependencyModel.ClassInfo callerClass = model.getClass(callerFQN);
                if (callerClass == null) continue;

                resolveCalleeFQN(call, pkgFQN, model, functionOwnerIndex)
                        .filter(t -> !t.equals(callerFQN))   // no self-reference
                        .ifPresent(targetFQN -> {
                    EdgeKind kind = call.isNewPattern() ? EdgeKind.INSTANTIATES : EdgeKind.CALLS;
                    callerClass.addDependency(targetFQN, kind);
                    // MethodInfo call tracking
                    String callerMethod = callerMethodName(call.callerFunction());
                    DependencyModel.MethodInfo mi = findMethod(callerClass, callerMethod);
                    if (mi != null) {
                        String calleeKey = targetFQN + "." + call.calleeName();
                        mi.methodCalls.merge(calleeKey, 1, Integer::sum);
                    }
                });
            }

            // Package-level vars → USES on DefaultPackageClass
            String baseName  = fileBaseName(file.filePath());
            boolean merge    = baseName.equals(file.packageName());
            String defaultPkgFQN = merge
                    ? pkgFQN + "." + baseName
                    : pkgFQN + "." + file.packageName();

            for (ParsedGoFile.VarDecl var : file.vars()) {
                if (var.qualifiedPkg() == null || var.qualifiedPkg().isEmpty()) continue;
                String varPkgFQN = packageFQN(var.qualifiedPkg());
                String base = baseTypeName(var.typeRef());
                if (base == null) continue;
                lookupInPackage(varPkgFQN, base, model, functionOwnerIndex).ifPresent(targetFQN -> {
                    DependencyModel.ClassInfo dpClass = model.getClass(defaultPkgFQN);
                    if (dpClass != null) dpClass.addDependency(targetFQN, EdgeKind.USES);
                });
            }
        }

        PackageHierarchyBuilder.buildPackageHierarchy(model);
        return model;
    }

    // ── FQN helpers ───────────────────────────────────────────────────────────

    /**
     * Converts a full Go import path to the S202 package FQN by stripping the
     * module prefix and replacing slashes with dots.
     * "go.etcd.io/etcd/v3/server/embed" → "server.embed"
     */
    String packageFQN(String importPath) {
        if (importPath == null || importPath.isEmpty()) return "";
        if (importPath.equals(moduleName)) {
            // root package: use last segment of module path
            int slash = moduleName.lastIndexOf('/');
            return slash >= 0 ? moduleName.substring(slash + 1) : moduleName;
        }
        String prefix = moduleName + "/";
        if (importPath.startsWith(prefix)) {
            return importPath.substring(prefix.length()).replace('/', '.');
        }
        return importPath.replace('/', '.');
    }

    // ── Type-assignment helpers ───────────────────────────────────────────────

    /**
     * Determines the dominant local type for a free function using the
     * concept-document heuristic:
     * <ol>
     *   <li>Unwrap {@code *}, {@code []}, {@code map[K]}, generic {@code [T]}.</li>
     *   <li>Skip qualified names (contain ".") and stdlib primitives.</li>
     *   <li>First matching return type wins; first matching parameter type is fallback.</li>
     * </ol>
     * Returns {@code null} when no local exported type matches.
     */
    String dominantType(ParsedGoFile.FunctionDecl fn, Map<String, String> localTypes) {
        // Try return types first
        for (String result : fn.results()) {
            String base = unwrapBaseType(result);
            if (isLocalExportedType(base, localTypes)) return base;
        }
        // Fallback: first parameter
        for (String param : fn.params()) {
            String base = unwrapBaseType(param);
            if (isLocalExportedType(base, localTypes)) return base;
        }
        return null;
    }

    /**
     * Unwraps Go type expressions to their base type name:
     * {@code *Client} → {@code Client}, {@code []*Client} → {@code Client},
     * {@code map[string]*Client} → {@code Client}, {@code Set[T]} → {@code Set}.
     */
    public static String unwrapBaseType(String typeExpr) {
        if (typeExpr == null || typeExpr.isBlank()) return null;
        String t = typeExpr.trim();
        // strip pointer(s)
        while (t.startsWith("*")) t = t.substring(1).trim();
        // strip slice
        if (t.startsWith("[]")) { t = t.substring(2).trim(); while (t.startsWith("*")) t = t.substring(1).trim(); }
        // strip map value
        if (t.startsWith("map[")) {
            int close = t.indexOf(']');
            if (close >= 0) { t = t.substring(close + 1).trim(); while (t.startsWith("*")) t = t.substring(1).trim(); }
        }
        // strip generic params
        int bracket = t.indexOf('[');
        if (bracket >= 0) t = t.substring(0, bracket);
        return t.isBlank() ? null : t;
    }

    private boolean isLocalExportedType(String base, Map<String, String> localTypes) {
        if (base == null) return false;
        if (base.contains(".")) return false;           // qualified → external
        if (STDLIB_PRIMITIVES.contains(base)) return false;
        String fqn = localTypes.get(base);
        return fqn != null;                             // exported (fqn != null) and in local index
    }

    // ── ClassInfo factory helpers ─────────────────────────────────────────────

    private static String fileBaseName(String filePath) {
        String name = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        return name.endsWith(".go") ? name.substring(0, name.length() - 3) : name;
    }

    private static boolean isExported(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private void ensureClassInfo(DependencyModel model, String fqn, String simple,
                                  String pkgFQN, boolean iface) {
        if (model.getClass(fqn) == null) {
            model.addClass(fqn, new DependencyModel.ClassInfo(fqn, simple, pkgFQN, iface));
        }
    }

    private void ensureDefaultClass(DependencyModel model, String fqn,
                                     String simple, String pkgFQN) {
        ensureClassInfo(model, fqn, simple, pkgFQN, false);
    }

    private void ensureDefaultPackageClass(DependencyModel model, String fqn,
                                            String simple, String pkgFQN) {
        ensureClassInfo(model, fqn, simple, pkgFQN, false);
    }

    // ── Descriptor / method helpers ───────────────────────────────────────────

    static String descriptor(List<String> params) {
        return "(" + String.join(", ", params) + ")";
    }

    private static DependencyModel.MethodInfo findMethod(DependencyModel.ClassInfo cls, String name) {
        for (DependencyModel.MethodInfo mi : cls.methods.values()) {
            if (mi.name.equals(name)) return mi;
        }
        return null;
    }

    private static String callerMethodName(String callerFunction) {
        if (callerFunction == null) return null;
        int dot = callerFunction.indexOf('.');
        return dot >= 0 ? callerFunction.substring(dot + 1) : callerFunction;
    }

    // ── Dependency resolution helpers ─────────────────────────────────────────

    private Optional<String> resolveTypeRef(String typeExpr, String callerPkgFQN,
                                             DependencyModel model,
                                             Map<String, String> fnOwnerIndex) {
        String base = unwrapBaseType(typeExpr);
        if (base == null) return Optional.empty();
        if (base.contains(".")) {
            // qualified: "pkg.Type" — we don't have the import path here, so we
            // scan for a ClassInfo whose simpleName matches in any known package
            String typeName = base.substring(base.lastIndexOf('.') + 1);
            String pkgAlias = base.substring(0, base.lastIndexOf('.'));
            // Try to find a ClassInfo with that simpleName in a package whose last segment matches alias
            return model.getAllClasses().values().stream()
                    .filter(ci -> ci.simpleName.equals(typeName)
                            && (ci.packageName.endsWith("." + pkgAlias) || ci.packageName.equals(pkgAlias)))
                    .map(ci -> ci.fullName)
                    .findFirst();
        }
        // unqualified: same package
        String candidate = callerPkgFQN + "." + base;
        if (model.getClass(candidate) != null) return Optional.of(candidate);
        return Optional.empty();
    }

    private Optional<String> resolveFieldRef(ParsedGoFile.FieldDecl field, String callerPkgFQN,
                                              DependencyModel model,
                                              Map<String, String> fnOwnerIndex) {
        String base = unwrapBaseType(field.typeRef());
        if (base == null) return Optional.empty();

        if (field.qualifiedPkg() != null && !field.qualifiedPkg().isEmpty()) {
            // cross-package field: look up base type in target package
            String targetPkgFQN = packageFQN(field.qualifiedPkg());
            return lookupInPackage(targetPkgFQN, baseTypeName(base), model, fnOwnerIndex);
        }
        // same package
        String candidate = callerPkgFQN + "." + base;
        if (model.getClass(candidate) != null) return Optional.of(candidate);
        return Optional.empty();
    }

    private String resolveFunctionOwnerFQN(ParsedGoFile.FunctionDecl fn, String pkgFQN,
                                            Map<String, String> fnOwnerIndex) {
        if (fn.receiver() != null && !fn.receiver().isEmpty()) {
            String key = pkgFQN + "." + fn.receiver() + "." + fn.name();
            return fnOwnerIndex.getOrDefault(key, fnOwnerIndex.get(pkgFQN + "." + fn.receiver()));
        }
        return fnOwnerIndex.get(pkgFQN + "." + fn.name());
    }

    private String resolveCallerFQN(String callerFunction, String pkgFQN,
                                     Map<String, String> fnOwnerIndex) {
        if (callerFunction == null) return null;
        int dot = callerFunction.indexOf('.');
        if (dot >= 0) {
            // "TypeName.MethodName" — receiver is type
            String typeName = callerFunction.substring(0, dot);
            String key = pkgFQN + "." + typeName + "." + callerFunction.substring(dot + 1);
            String fqn = fnOwnerIndex.get(key);
            if (fqn != null) return fqn;
            // fallback: plain type lookup
            return fnOwnerIndex.get(pkgFQN + "." + typeName);
        }
        return fnOwnerIndex.get(pkgFQN + "." + callerFunction);
    }

    private Optional<String> resolveCalleeFQN(ParsedGoFile.CallRef call, String callerPkgFQN,
                                               DependencyModel model,
                                               Map<String, String> fnOwnerIndex) {
        String calleePkg = call.calleePkg();
        String targetPkgFQN = (calleePkg == null || calleePkg.isEmpty())
                ? callerPkgFQN
                : packageFQN(calleePkg);

        String calleeName = call.calleeName();
        int dot = calleeName.indexOf('.');
        String symbolName = dot >= 0 ? calleeName.substring(dot + 1) : calleeName;
        String typeName   = dot >= 0 ? calleeName.substring(0, dot)  : null;

        // Try function-owner index first
        if (typeName != null) {
            String key = targetPkgFQN + "." + typeName + "." + symbolName;
            if (fnOwnerIndex.containsKey(key)) return Optional.of(fnOwnerIndex.get(key));
        }
        String key = targetPkgFQN + "." + symbolName;
        if (fnOwnerIndex.containsKey(key)) return Optional.of(fnOwnerIndex.get(key));

        // Fall back to direct Typ-ClassInfo lookup
        return lookupInPackage(targetPkgFQN, symbolName, model, fnOwnerIndex);
    }

    Optional<String> lookupInPackage(String pkgFQN, String symbolName,
                                      DependencyModel model,
                                      Map<String, String> fnOwnerIndex) {
        if (symbolName == null) return Optional.empty();
        String direct = pkgFQN + "." + symbolName;
        if (model.getClass(direct) != null) return Optional.of(direct);
        String indexed = fnOwnerIndex.get(pkgFQN + "." + symbolName);
        if (indexed != null) return Optional.of(indexed);
        return Optional.empty();
    }

    private static String baseTypeName(String typeRef) {
        String base = unwrapBaseType(typeRef);
        if (base == null) return null;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }
}
