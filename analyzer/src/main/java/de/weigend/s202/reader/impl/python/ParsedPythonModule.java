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
package de.weigend.s202.reader.impl.python;

import java.util.List;
import java.util.Map;

/**
 * Parser-level Python module representation. It deliberately has no S202 level
 * or architecture semantics; resolution into DependencyModel happens later.
 */
public record ParsedPythonModule(
        String moduleName,
        String sourcePath,
        List<ImportRef> imports,
        List<ClassDef> classes,
        List<ScopeDef> scopes) {

    public ParsedPythonModule {
        imports = imports == null ? List.of() : List.copyOf(imports);
        classes = classes == null ? List.of() : List.copyOf(classes);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public record ImportRef(
            String kind,
            String module,
            String name,
            String alias,
            int level,
            boolean star,
            int line) {
    }

    public record ClassDef(
            String name,
            List<String> bases,
            List<String> decorators,
            int line) {

        public ClassDef {
            bases = bases == null ? List.of() : List.copyOf(bases);
            decorators = decorators == null ? List.of() : List.copyOf(decorators);
        }
    }

    public record ScopeDef(
            String name,
            String descriptor,
            String kind,
            String className,
            List<CallRef> calls,
            List<String> annotations,
            List<AssignmentRef> assignments,
            Map<String, String> parameterAnnotations) {

        public ScopeDef {
            calls = calls == null ? List.of() : List.copyOf(calls);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
            parameterAnnotations = parameterAnnotations == null ? Map.of() : Map.copyOf(parameterAnnotations);
        }
    }

    public record CallRef(String expression, int line) {
    }

    public record AssignmentRef(
            String target,
            String value,
            boolean valueIsCall,
            String annotation,
            int line) {
    }
}
