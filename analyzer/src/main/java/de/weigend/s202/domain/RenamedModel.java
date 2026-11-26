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
import de.weigend.s202.reader.TypeUse;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A copy of a model in which every class has another name, and with it
 * another package: everything that refers to a class of the model -
 * dependencies, their kinds, type uses, calls - is renamed along. What lies
 * outside the analysis keeps its name. The way to another hierarchy over the
 * same classes without teaching the analysis a second one.
 */
final class RenamedModel {

    private final DependencyModel model = new DependencyModel();
    /** Class of the original → its name here. */
    private final Map<String, String> renamed = new HashMap<>();

    /** @param newName the new full name of a class; its package is everything before the last dot */
    RenamedModel(DependencyModel original, Function<DependencyModel.ClassInfo, String> newName) {
        original.getAllClasses().forEach((name, info) -> renamed.put(name, newName.apply(info)));
        original.getAllClasses().forEach((name, info) -> model.addClass(renamed.get(name), copy(info)));
        original.getAllModules().values().forEach(model::addModule);
        model.setPackages(packages(model));
    }

    DependencyModel model() {
        return model;
    }

    /** The new name of a class of the original; other names stay as they are. */
    String rename(String name) {
        return renamed.getOrDefault(name, name);
    }

    private DependencyModel.ClassInfo copy(DependencyModel.ClassInfo info) {
        String name = renamed.get(info.fullName);
        int dot = name.lastIndexOf('.');
        String pkg = dot < 0 ? "" : name.substring(0, dot);
        DependencyModel.ClassInfo copy = new DependencyModel.ClassInfo(name, info.simpleName, pkg, info.interfaceType);
        copy.setOrigin(info.getOrigin());
        info.dependencies.forEach(target -> copy.dependencies.add(rename(target)));
        info.dependencyKinds.forEach((target, kinds) -> copy.dependencyKinds.put(rename(target), EnumSet.copyOf(kinds)));
        info.externalDependencies.forEach((target, kinds) ->
                copy.externalDependencies.put(target, EnumSet.copyOf(kinds)));
        info.typeUses.forEach((target, uses) -> copy.typeUses.put(rename(target), new HashSet<TypeUse>(uses)));
        info.methods.forEach((key, method) -> {
            DependencyModel.MethodInfo methodCopy = new DependencyModel.MethodInfo(method.name, method.descriptor);
            methodCopy.complexity = method.complexity;
            methodCopy.lines = method.lines;
            method.methodCalls.forEach((call, count) -> methodCopy.methodCalls.put(call(call), count));
            method.methodCallDescriptors.forEach((call, descriptors) ->
                    methodCopy.methodCallDescriptors.put(call(call), new HashSet<>(descriptors)));
            copy.methods.put(key, methodCopy);
        });
        return copy;
    }

    /** {@code com.x.Owner$Inner.method}: the class the owner belongs to gets its new name. */
    private String call(String call) {
        int dot = call.lastIndexOf('.');
        if (dot < 0) {
            return call;
        }
        String owner = call.substring(0, dot);
        int nested = owner.indexOf('$');
        String outer = nested < 0 ? owner : owner.substring(0, nested);
        String newOuter = renamed.get(outer);
        return newOuter == null ? call : newOuter + call.substring(outer.length());
    }

    private static Map<String, DependencyModel.PackageInfo> packages(DependencyModel model) {
        Map<String, DependencyModel.PackageInfo> packages = new LinkedHashMap<>();
        for (String className : new TreeSet<>(model.getAllClassNames())) {
            String pkg = model.getClass(className).packageName;
            if (pkg.isEmpty()) {
                continue;
            }
            String parent = "";
            for (String part : pkg.split("\\.")) {
                String current = parent.isEmpty() ? part : parent + "." + part;
                if (!packages.containsKey(current)) {
                    packages.put(current, new DependencyModel.PackageInfo(current, part));
                    if (!parent.isEmpty()) {
                        packages.get(parent).childPackages.add(current);
                    }
                }
                parent = current;
            }
            packages.get(pkg).classNames.add(className);
        }
        return packages;
    }
}
