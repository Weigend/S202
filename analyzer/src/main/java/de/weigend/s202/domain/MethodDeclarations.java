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
package de.weigend.s202.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the class that declares a method. A call names the class it is made
 * on; the method itself may be inherited from one of that class's supertypes.
 */
public final class MethodDeclarations {

    private MethodDeclarations() {
    }

    /**
     * @param descriptor the method's descriptor; {@code null} accepts any overload
     * @return {@code className} or the supertype of it that declares the
     *         method; {@code null} when no analysed class does
     */
    public static String declaringClass(DependencyModel model, String className, String method, String descriptor) {
        if (model == null || className == null || method == null) {
            return null;
        }
        return find(model, className, method, descriptor, new HashSet<>());
    }

    private static String find(DependencyModel model, String className, String method, String descriptor,
                               Set<String> visited) {
        DependencyModel.ClassInfo info = model.getClass(className);
        if (info == null || !visited.add(className)) {
            return null;
        }
        if (declares(info, method, descriptor)) {
            return className;
        }
        // The superclass wins over interfaces, as it does in Java.
        for (EdgeKind kind : new EdgeKind[] {EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS}) {
            for (String superType : targets(info, kind)) {
                String found = find(model, superType, method, descriptor, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean declares(DependencyModel.ClassInfo info, String method, String descriptor) {
        if (descriptor != null) {
            return info.getMethod(method, descriptor) != null;
        }
        return info.methods.values().stream().anyMatch(m -> m.name.equals(method));
    }

    private static Set<String> targets(DependencyModel.ClassInfo info, EdgeKind kind) {
        Set<String> targets = new TreeSet<>();
        for (Map.Entry<String, ? extends Set<EdgeKind>> dependency : info.dependencyKinds.entrySet()) {
            if (dependency.getValue().contains(kind)) {
                targets.add(dependency.getKey());
            }
        }
        return targets;
    }
}
