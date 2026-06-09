//go:build ignore

// s202_go_ast.go — Structure202 Go AST extractor.
// Called as: go run s202_go_ast.go <input.json> <output.json>
//
// Input JSON:  { "moduleName": "...", "moduleRoot": "/abs/path", "files": ["/abs/..."] }
// Output JSON: [ { ParsedGoFile }, ... ]

package main

import (
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strings"
)

// ── Input / Output types ──────────────────────────────────────────────────────

type Input struct {
	ModuleName string   `json:"moduleName"`
	ModuleRoot string   `json:"moduleRoot"`
	Files      []string `json:"files"`
}

type FileOutput struct {
	FilePath    string         `json:"filePath"`
	PackageName string         `json:"packageName"`
	ImportPath  string         `json:"importPath"`
	Imports     []ImportDecl   `json:"imports"`
	Types       []TypeDecl     `json:"types"`
	Functions   []FunctionDecl `json:"functions"`
	Vars        []VarDecl      `json:"vars"`
	Calls       []CallRef      `json:"calls"`
}

type ImportDecl struct {
	Alias string `json:"alias"`
	Path  string `json:"path"`
}

type TypeDecl struct {
	Name       string      `json:"name"`
	Kind       string      `json:"kind"`
	TypeParams []string    `json:"typeParams"`
	BaseType   string      `json:"baseType"` // underlying type for kind="type", e.g. "[]Page"
	Embeds     []string    `json:"embeds"`
	Fields     []FieldDecl `json:"fields"`
}

type FieldDecl struct {
	Name         string `json:"name"`
	TypeRef      string `json:"typeRef"`
	QualifiedPkg string `json:"qualifiedPkg"`
}

type FunctionDecl struct {
	Name       string   `json:"name"`
	Receiver   string   `json:"receiver"`
	TypeParams []string `json:"typeParams"`
	Params     []string `json:"params"`
	Results    []string `json:"results"`
}

type VarDecl struct {
	Name         string `json:"name"`
	TypeRef      string `json:"typeRef"`
	QualifiedPkg string `json:"qualifiedPkg"`
}

type CallRef struct {
	CallerFunction string `json:"callerFunction"`
	CalleePkg      string `json:"calleePkg"`
	CalleeName     string `json:"calleeName"`
	IsNewPattern   bool   `json:"isNewPattern"`
}

// ── Main ─────────────────────────────────────────────────────────────────────

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: s202_go_ast.go <input.json> <output.json>")
		os.Exit(1)
	}

	inputData, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "read input:", err)
		os.Exit(1)
	}

	var input Input
	if err = json.Unmarshal(inputData, &input); err != nil {
		fmt.Fprintln(os.Stderr, "parse input:", err)
		os.Exit(1)
	}

	outputs := make([]FileOutput, 0, len(input.Files))
	for _, filePath := range input.Files {
		out, err := parseFile(filePath, input.ModuleName, input.ModuleRoot)
		if err != nil {
			fmt.Fprintf(os.Stderr, "warning: skipping %s: %v\n", filePath, err)
			continue
		}
		outputs = append(outputs, out)
	}

	result, err := json.Marshal(outputs)
	if err != nil {
		fmt.Fprintln(os.Stderr, "marshal output:", err)
		os.Exit(1)
	}
	if err = os.WriteFile(os.Args[2], result, 0600); err != nil {
		fmt.Fprintln(os.Stderr, "write output:", err)
		os.Exit(1)
	}
}

// ── File parser ───────────────────────────────────────────────────────────────

func parseFile(absPath, moduleName, moduleRoot string) (FileOutput, error) {
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, absPath, nil, 0)
	if err != nil {
		return FileOutput{}, err
	}

	relPath, _ := filepath.Rel(moduleRoot, absPath)
	relPath = filepath.ToSlash(relPath)
	dir := filepath.ToSlash(filepath.Dir(relPath))

	var importPath string
	if dir == "." || dir == "" {
		importPath = moduleName
	} else {
		importPath = moduleName + "/" + dir
	}

	aliases := buildAliasMap(f.Imports)

	return FileOutput{
		FilePath:    relPath,
		PackageName: f.Name.Name,
		ImportPath:  importPath,
		Imports:     buildImports(f.Imports),
		Types:       extractTypes(f, aliases),
		Functions:   extractFunctions(f),
		Vars:        extractVars(f, aliases),
		Calls:       extractCalls(f, aliases),
	}, nil
}

// ── Imports ───────────────────────────────────────────────────────────────────

func buildAliasMap(specs []*ast.ImportSpec) map[string]string {
	m := make(map[string]string)
	for _, spec := range specs {
		path := strings.Trim(spec.Path.Value, `"`)
		if spec.Name != nil {
			name := spec.Name.Name
			if name != "_" && name != "." {
				m[name] = path
			}
		} else {
			parts := strings.Split(path, "/")
			m[parts[len(parts)-1]] = path
		}
	}
	return m
}

func buildImports(specs []*ast.ImportSpec) []ImportDecl {
	result := make([]ImportDecl, 0, len(specs))
	for _, spec := range specs {
		alias := ""
		if spec.Name != nil {
			alias = spec.Name.Name
		}
		result = append(result, ImportDecl{
			Alias: alias,
			Path:  strings.Trim(spec.Path.Value, `"`),
		})
	}
	return result
}

// ── Types ─────────────────────────────────────────────────────────────────────

func extractTypes(f *ast.File, aliases map[string]string) []TypeDecl {
	var result []TypeDecl
	for _, decl := range f.Decls {
		gd, ok := decl.(*ast.GenDecl)
		if !ok || gd.Tok != token.TYPE {
			continue
		}
		for _, spec := range gd.Specs {
			ts, ok := spec.(*ast.TypeSpec)
			if !ok {
				continue
			}
			td := TypeDecl{
				Name:       ts.Name.Name,
				TypeParams: extractTypeParams(ts),
				Embeds:     []string{},
				Fields:     []FieldDecl{},
			}
			switch t := ts.Type.(type) {
			case *ast.StructType:
				td.Kind = "struct"
				td.Embeds, td.Fields = extractStructFields(t, aliases)
			case *ast.InterfaceType:
				td.Kind = "interface"
				td.Embeds = extractInterfaceEmbeds(t)
			default:
				td.Kind = "type"
				td.BaseType = exprToString(ts.Type)
			}
			result = append(result, td)
		}
	}
	return result
}

func extractTypeParams(ts *ast.TypeSpec) []string {
	if ts.TypeParams == nil {
		return []string{}
	}
	var params []string
	for _, field := range ts.TypeParams.List {
		for _, name := range field.Names {
			params = append(params, name.Name)
		}
	}
	return params
}

func extractStructFields(st *ast.StructType, aliases map[string]string) (embeds []string, fields []FieldDecl) {
	embeds = []string{}
	fields = []FieldDecl{}
	if st.Fields == nil {
		return
	}
	for _, field := range st.Fields.List {
		typeStr := exprToString(field.Type)
		if len(field.Names) == 0 {
			embeds = append(embeds, typeStr)
		} else {
			qualPkg := resolveQualPkg(field.Type, aliases)
			for _, name := range field.Names {
				fields = append(fields, FieldDecl{
					Name:         name.Name,
					TypeRef:      typeStr,
					QualifiedPkg: qualPkg,
				})
			}
		}
	}
	return
}

func extractInterfaceEmbeds(it *ast.InterfaceType) []string {
	embeds := []string{}
	if it.Methods == nil {
		return embeds
	}
	for _, method := range it.Methods.List {
		if len(method.Names) == 0 {
			embeds = append(embeds, exprToString(method.Type))
		}
	}
	return embeds
}

// ── Functions ─────────────────────────────────────────────────────────────────

func extractFunctions(f *ast.File) []FunctionDecl {
	var result []FunctionDecl
	for _, decl := range f.Decls {
		fd, ok := decl.(*ast.FuncDecl)
		if !ok {
			continue
		}
		fn := FunctionDecl{
			Name:       fd.Name.Name,
			TypeParams: extractFuncTypeParams(fd),
			Params:     []string{},
			Results:    []string{},
		}
		if fd.Recv != nil && len(fd.Recv.List) > 0 {
			fn.Receiver = receiverTypeName(fd.Recv.List[0].Type)
		}
		if fd.Type.Params != nil {
			for _, param := range fd.Type.Params.List {
				typeStr := exprToString(param.Type)
				count := len(param.Names)
				if count == 0 {
					count = 1
				}
				for i := 0; i < count; i++ {
					fn.Params = append(fn.Params, typeStr)
				}
			}
		}
		if fd.Type.Results != nil {
			for _, res := range fd.Type.Results.List {
				typeStr := exprToString(res.Type)
				count := len(res.Names)
				if count == 0 {
					count = 1
				}
				for i := 0; i < count; i++ {
					fn.Results = append(fn.Results, typeStr)
				}
			}
		}
		result = append(result, fn)
	}
	return result
}

func extractFuncTypeParams(fd *ast.FuncDecl) []string {
	if fd.Type.TypeParams == nil {
		return []string{}
	}
	var params []string
	for _, field := range fd.Type.TypeParams.List {
		for _, name := range field.Names {
			params = append(params, name.Name)
		}
	}
	return params
}

func receiverTypeName(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.StarExpr:
		return receiverTypeName(t.X)
	case *ast.Ident:
		return t.Name
	case *ast.IndexExpr:
		return receiverTypeName(t.X)
	}
	return ""
}

// ── Variables ─────────────────────────────────────────────────────────────────

func extractVars(f *ast.File, aliases map[string]string) []VarDecl {
	var result []VarDecl
	for _, decl := range f.Decls {
		gd, ok := decl.(*ast.GenDecl)
		if !ok || gd.Tok != token.VAR {
			continue
		}
		for _, spec := range gd.Specs {
			vs, ok := spec.(*ast.ValueSpec)
			if !ok {
				continue
			}
			var typeRef, qualPkg string
			if vs.Type != nil {
				typeRef = exprToString(vs.Type)
				qualPkg = resolveQualPkg(vs.Type, aliases)
			} else if len(vs.Values) > 0 {
				typeRef, qualPkg = inferTypeFromRHS(vs.Values[0], aliases)
			}
			for _, name := range vs.Names {
				result = append(result, VarDecl{
					Name:         name.Name,
					TypeRef:      typeRef,
					QualifiedPkg: qualPkg,
				})
			}
		}
	}
	return result
}

func inferTypeFromRHS(expr ast.Expr, aliases map[string]string) (typeRef, qualPkg string) {
	call, ok := expr.(*ast.CallExpr)
	if !ok {
		return
	}
	sel, ok := call.Fun.(*ast.SelectorExpr)
	if !ok {
		return
	}
	ident, ok := sel.X.(*ast.Ident)
	if !ok {
		return
	}
	if importPath, found := aliases[ident.Name]; found {
		typeRef = ident.Name + "." + sel.Sel.Name
		qualPkg = importPath
	}
	return
}

// ── Calls ─────────────────────────────────────────────────────────────────────

func extractCalls(f *ast.File, aliases map[string]string) []CallRef {
	var result []CallRef
	for _, decl := range f.Decls {
		fd, ok := decl.(*ast.FuncDecl)
		if !ok || fd.Body == nil {
			continue
		}
		callerName := callerFunctionName(fd)
		ast.Inspect(fd.Body, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			if cr := resolveCall(call, callerName, aliases); cr != nil {
				result = append(result, *cr)
			}
			return true
		})
	}
	return result
}

func callerFunctionName(fd *ast.FuncDecl) string {
	if fd.Recv == nil || len(fd.Recv.List) == 0 {
		return fd.Name.Name
	}
	return receiverTypeName(fd.Recv.List[0].Type) + "." + fd.Name.Name
}

func resolveCall(call *ast.CallExpr, caller string, aliases map[string]string) *CallRef {
	sel, ok := call.Fun.(*ast.SelectorExpr)
	if !ok {
		return nil
	}
	ident, ok := sel.X.(*ast.Ident)
	if !ok {
		return nil
	}
	if importPath, found := aliases[ident.Name]; found {
		name := sel.Sel.Name
		return &CallRef{
			CallerFunction: caller,
			CalleePkg:      importPath,
			CalleeName:     name,
			IsNewPattern:   strings.HasPrefix(name, "New"),
		}
	}
	return nil
}

// ── Type expression helpers ───────────────────────────────────────────────────

func exprToString(expr ast.Expr) string {
	if expr == nil {
		return ""
	}
	switch e := expr.(type) {
	case *ast.Ident:
		return e.Name
	case *ast.StarExpr:
		return "*" + exprToString(e.X)
	case *ast.ArrayType:
		if e.Len == nil {
			return "[]" + exprToString(e.Elt)
		}
		return "[...]" + exprToString(e.Elt)
	case *ast.MapType:
		return "map[" + exprToString(e.Key) + "]" + exprToString(e.Value)
	case *ast.SelectorExpr:
		return exprToString(e.X) + "." + e.Sel.Name
	case *ast.IndexExpr:
		return exprToString(e.X) + "[" + exprToString(e.Index) + "]"
	case *ast.FuncType:
		return "func(...)"
	case *ast.ChanType:
		return "chan " + exprToString(e.Value)
	case *ast.Ellipsis:
		return "..." + exprToString(e.Elt)
	case *ast.InterfaceType:
		return "interface{}"
	case *ast.StructType:
		return "struct{}"
	}
	return fmt.Sprintf("%T", expr)
}

func resolveQualPkg(expr ast.Expr, aliases map[string]string) string {
	switch e := expr.(type) {
	case *ast.StarExpr:
		return resolveQualPkg(e.X, aliases)
	case *ast.ArrayType:
		return resolveQualPkg(e.Elt, aliases)
	case *ast.MapType:
		return resolveQualPkg(e.Value, aliases)
	case *ast.IndexExpr:
		return resolveQualPkg(e.X, aliases)
	case *ast.SelectorExpr:
		if ident, ok := e.X.(*ast.Ident); ok {
			if importPath, found := aliases[ident.Name]; found {
				return importPath
			}
		}
	}
	return ""
}
