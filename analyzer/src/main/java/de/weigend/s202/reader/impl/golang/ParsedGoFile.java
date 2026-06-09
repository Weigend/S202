package de.weigend.s202.reader.impl.golang;

import java.util.List;

/**
 * Parser-level Go file representation. No S202 architecture semantics; resolution
 * into DependencyModel happens in GoDependencyResolver.
 */
public record ParsedGoFile(
        String filePath,      // relative to module root, e.g. "client/v3/client.go"
        String packageName,   // Go package declaration, e.g. "v3"
        String importPath,    // full Go import path, e.g. "go.etcd.io/etcd/v3/client/v3"
        List<ImportDecl> imports,
        List<TypeDecl> types,
        List<FunctionDecl> functions,
        List<VarDecl> vars,
        List<CallRef> calls) {

    public ParsedGoFile {
        imports   = imports   == null ? List.of() : List.copyOf(imports);
        types     = types     == null ? List.of() : List.copyOf(types);
        functions = functions == null ? List.of() : List.copyOf(functions);
        vars      = vars      == null ? List.of() : List.copyOf(vars);
        calls     = calls     == null ? List.of() : List.copyOf(calls);
    }

    /** An import declaration in a Go source file. */
    public record ImportDecl(
            String alias,     // local alias ("" = use package name, "_" = blank import)
            String path) {    // full import path
    }

    /** An exported or unexported type definition (struct or interface). */
    public record TypeDecl(
            String name,
            String kind,              // "struct", "interface", or "type" (alias/definition)
            List<String> typeParams,  // generic type param names, e.g. ["T"]
            String baseType,          // underlying type expr for kind="type", e.g. "[]Page"
            List<String> embeds,      // embedded type expressions, e.g. ["*etcdserver.EtcdServer"]
            List<FieldDecl> fields) { // struct fields (empty for interfaces)

        public TypeDecl {
            typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
            embeds     = embeds     == null ? List.of() : List.copyOf(embeds);
            fields     = fields     == null ? List.of() : List.copyOf(fields);
            if (baseType == null) baseType = "";
        }
    }

    /** A struct field declaration. */
    public record FieldDecl(
            String name,
            String typeRef,       // type expression as written, e.g. "[]*clientv3.Client"
            String qualifiedPkg) { // resolved full import path of the type's package, "" if same module
    }

    /** A function or method declaration. */
    public record FunctionDecl(
            String name,
            String receiver,          // receiver base type name (no pointer), "" for free functions
            List<String> typeParams,  // generic type params
            List<String> params,      // parameter type expressions
            List<String> results) {   // result type expressions

        public FunctionDecl {
            typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
            params     = params     == null ? List.of() : List.copyOf(params);
            results    = results    == null ? List.of() : List.copyOf(results);
        }
    }

    /** A package-level variable declaration. */
    public record VarDecl(
            String name,
            String typeRef,       // type expression, may be inferred from RHS
            String qualifiedPkg) { // resolved full import path for the type's package, "" if unknown
    }

    /** A function call observed within this file. */
    public record CallRef(
            String callerFunction, // "FuncName" for free function, "TypeName.MethodName" for method
            String calleePkg,      // full import path of the callee's package, "" if same package
            String calleeName,     // just the symbol name, e.g. "NewServer" or "EtcdServer.Close"
            boolean isNewPattern)  // true when calleeName starts with "New" (INSTANTIATES heuristic)
    {
    }
}
