#!/usr/bin/env python3
import ast
import json
import sys


def dotted(node):
    if node is None:
        return None
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        base = dotted(node.value)
        return f"{base}.{node.attr}" if base else node.attr
    if isinstance(node, ast.Call):
        return dotted(node.func)
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.Subscript):
        return dotted(node.value)
    if isinstance(node, ast.BinOp):
        left = dotted(node.left)
        right = dotted(node.right)
        if left and right:
            return f"{left}.{right}"
        return left or right
    return None


def annotation_names(node):
    names = []

    def visit(n):
        value = dotted(n)
        if value:
            names.append(value)
        for child in ast.iter_child_nodes(n):
            visit(child)

    if node is not None:
        visit(node)
    return names


def args_descriptor(args):
    names = []
    for arg in getattr(args, "posonlyargs", []):
        names.append(arg.arg)
    for arg in args.args:
        names.append(arg.arg)
    if args.vararg is not None:
        names.append("*" + args.vararg.arg)
    for arg in args.kwonlyargs:
        names.append(arg.arg)
    if args.kwarg is not None:
        names.append("**" + args.kwarg.arg)
    return "(" + ", ".join(names) + ")"


def parameter_annotations(args):
    out = {}
    all_args = []
    all_args.extend(getattr(args, "posonlyargs", []))
    all_args.extend(args.args)
    if args.vararg is not None:
        all_args.append(args.vararg)
    all_args.extend(args.kwonlyargs)
    if args.kwarg is not None:
        all_args.append(args.kwarg)
    for arg in all_args:
        value = dotted(arg.annotation)
        if value:
            out[arg.arg] = value
    return out


def target_name(node):
    return dotted(node)


class ScopeCollector(ast.NodeVisitor):
    def __init__(self):
        self.calls = []
        self.annotations = []
        self.assignments = []

    def visit_FunctionDef(self, node):
        return

    def visit_AsyncFunctionDef(self, node):
        return

    def visit_ClassDef(self, node):
        return

    def visit_Call(self, node):
        expr = dotted(node.func)
        if expr:
            self.calls.append({"expression": expr, "line": getattr(node, "lineno", 0)})
        self.generic_visit(node)

    def visit_Assign(self, node):
        value = dotted(node.value.func) if isinstance(node.value, ast.Call) else dotted(node.value)
        value_is_call = isinstance(node.value, ast.Call)
        for target in node.targets:
            name = target_name(target)
            if name:
                self.assignments.append({
                    "target": name,
                    "value": value,
                    "valueIsCall": value_is_call,
                    "annotation": None,
                    "line": getattr(node, "lineno", 0),
                })
        self.generic_visit(node)

    def visit_AnnAssign(self, node):
        for name in annotation_names(node.annotation):
            self.annotations.append(name)
        value = None
        value_is_call = False
        if node.value is not None:
            value = dotted(node.value.func) if isinstance(node.value, ast.Call) else dotted(node.value)
            value_is_call = isinstance(node.value, ast.Call)
        target = target_name(node.target)
        if target:
            self.assignments.append({
                "target": target,
                "value": value,
                "valueIsCall": value_is_call,
                "annotation": dotted(node.annotation),
                "line": getattr(node, "lineno", 0),
            })
        self.generic_visit(node)

    def visit_arg(self, node):
        for name in annotation_names(node.annotation):
            self.annotations.append(name)


def collect_scope(name, descriptor, kind, class_name, body, args=None, extra_annotations=None):
    collector = ScopeCollector()
    for stmt in body:
        collector.visit(stmt)
    annotations = list(extra_annotations or [])
    annotations.extend(collector.annotations)
    if args is not None:
        for value in parameter_annotations(args).values():
            annotations.append(value)
    return {
        "name": name,
        "descriptor": descriptor,
        "kind": kind,
        "className": class_name,
        "calls": collector.calls,
        "annotations": sorted(set(a for a in annotations if a)),
        "assignments": collector.assignments,
        "parameterAnnotations": parameter_annotations(args) if args is not None else {},
    }


def function_scopes(function_node, prefix="", class_name=None):
    scope_name = f"{prefix}.{function_node.name}" if prefix else function_node.name
    annotations = []
    annotations.extend(annotation_names(function_node.returns))
    for decorator in function_node.decorator_list:
        name = dotted(decorator)
        if name:
            annotations.append(name)
    scopes = [collect_scope(
        scope_name,
        args_descriptor(function_node.args),
        "async_function" if isinstance(function_node, ast.AsyncFunctionDef) else "function",
        class_name,
        function_node.body,
        function_node.args,
        annotations,
    )]
    for stmt in function_node.body:
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)):
            scopes.extend(function_scopes(stmt, scope_name, class_name))
    return scopes


def parse_module(module_name, path):
    with open(path, "r", encoding="utf-8") as handle:
        source = handle.read()
    tree = ast.parse(source, filename=path)

    imports = []
    classes = []
    scopes = []

    for node in tree.body:
        if isinstance(node, ast.Import):
            for alias in node.names:
                imports.append({
                    "kind": "import",
                    "module": alias.name,
                    "name": None,
                    "alias": alias.asname,
                    "level": 0,
                    "star": False,
                    "line": getattr(node, "lineno", 0),
                })
        elif isinstance(node, ast.ImportFrom):
            for alias in node.names:
                imports.append({
                    "kind": "from",
                    "module": node.module or "",
                    "name": alias.name,
                    "alias": alias.asname,
                    "level": node.level,
                    "star": alias.name == "*",
                    "line": getattr(node, "lineno", 0),
                })

    module_annotations = []
    scopes.append(collect_scope("__module__", "()", "module", None, tree.body, None, module_annotations))

    for node in tree.body:
        if isinstance(node, ast.ClassDef):
            bases = [name for base in node.bases for name in annotation_names(base)]
            decorators = [name for deco in node.decorator_list for name in annotation_names(deco)]
            classes.append({
                "name": node.name,
                "bases": sorted(set(bases)),
                "decorators": sorted(set(decorators)),
                "line": getattr(node, "lineno", 0),
            })
            scopes.append(collect_scope(
                f"{node.name}.__classdef__",
                "()",
                "class",
                node.name,
                [],
                None,
                bases + decorators,
            ))
            for stmt in node.body:
                if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    scopes.extend(function_scopes(stmt, node.name, node.name))
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            scopes.extend(function_scopes(node))

    return {
        "moduleName": module_name,
        "sourcePath": path,
        "imports": imports,
        "classes": classes,
        "scopes": scopes,
    }


def main():
    if len(sys.argv) != 3:
        print("usage: s202_py_ast.py input.json output.json", file=sys.stderr)
        return 2

    with open(sys.argv[1], "r", encoding="utf-8") as handle:
        request = json.load(handle)

    modules = []
    errors = []
    for item in request.get("files", []):
        module_name = item["moduleName"]
        path = item["path"]
        try:
            modules.append(parse_module(module_name, path))
        except Exception as exc:
            errors.append({
                "moduleName": module_name,
                "path": path,
                "message": str(exc),
            })

    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        json.dump({"modules": modules, "errors": errors}, handle, ensure_ascii=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
