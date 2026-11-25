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
package de.weigend.s202.ui.features.details;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders recorded method names and descriptors the way they read in source code. */
final class MethodLabels {

    private static final Pattern TYPE = Pattern.compile("\\[*(?:[BCDFIJSZ]|L[^;]+;)");
    private static final Pattern JVM_DESCRIPTOR =
            Pattern.compile("\\(((?:\\[*(?:[BCDFIJSZ]|L[^;]+;))*)\\)\\[*(?:[BCDFIJSZV]|L[^;]+;)");
    private static final Pattern ANONYMOUS = Pattern.compile("(?<![^.])(\\d+)\\.");
    private static final Pattern LOCAL = Pattern.compile("(?<![^.])\\d+(?=[^\\d.])");
    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

    private MethodLabels() {
    }

    /** {@code run(String)}; constructors get the name of their class, lambdas that of their method. */
    static String method(String ownerFqn, String member, String descriptor) {
        int dot = member.lastIndexOf('.');
        String name = member.substring(dot + 1);
        String prefix = nestedPrefix(member.substring(0, dot + 1));
        if ("<clinit>".equals(name)) {
            return prefix + "static initializer";
        }
        if ("<init>".equals(name)) {
            // A nested class names itself, the outer one is the owner.
            String constructed = dot < 0 ? simple(ownerFqn) : prefix.substring(0, prefix.length() - 1);
            return constructed + parameters(descriptor);
        }
        Matcher lambda = LAMBDA.matcher(name);
        if (lambda.matches()) {
            return switch (lambda.group(1)) {
                case "new" -> (dot < 0 ? simple(ownerFqn) : prefix.substring(0, prefix.length() - 1)) + "() [lambda]";
                case "static" -> prefix + "static initializer [lambda]";
                // The lambda's own parameters say nothing about its method.
                default -> prefix + lambda.group(1) + "() [lambda]";
            };
        }
        return prefix + name + parameters(descriptor);
    }

    /** The name of a nested class or of a field in one. */
    static String nested(String name) {
        String readable = nestedPrefix(name + ".");
        return readable.substring(0, readable.length() - 1);
    }

    /** The compiler numbers anonymous and local classes: {@code 1.}, {@code 1Local.}. */
    private static String nestedPrefix(String prefix) {
        return ANONYMOUS.matcher(prefix.replace('$', '.')).replaceAll("anonymous class $1.")
                .transform(readable -> LOCAL.matcher(readable).replaceAll(""));
    }

    /** {@code (int, String[])}; a descriptor of another language stays as it is. */
    private static String parameters(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return "()";
        }
        Matcher jvm = JVM_DESCRIPTOR.matcher(descriptor);
        if (!jvm.matches()) {
            return descriptor.startsWith("(") ? descriptor : "(" + descriptor + ")";
        }
        List<String> types = new ArrayList<>();
        Matcher type = TYPE.matcher(jvm.group(1));
        while (type.find()) {
            types.add(typeName(type.group()));
        }
        return "(" + String.join(", ", types) + ")";
    }

    private static String typeName(String type) {
        int dimensions = type.lastIndexOf('[') + 1;
        String element = type.substring(dimensions);
        String name = switch (element.charAt(0)) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            default -> {
                String internal = element.substring(1, element.length() - 1);
                yield internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
            }
        };
        return name + "[]".repeat(dimensions);
    }

    static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
