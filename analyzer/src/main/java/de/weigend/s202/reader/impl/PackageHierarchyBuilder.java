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
package de.weigend.s202.reader.impl;

import de.weigend.s202.reader.DependencyModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the package hierarchy shared by language-specific readers.
 */
public final class PackageHierarchyBuilder {

    private PackageHierarchyBuilder() {
    }

    public static void buildPackageHierarchy(DependencyModel model) {
        model.setPackages(buildPackages(model));
    }

    public static Map<String, DependencyModel.PackageInfo> buildPackages(DependencyModel model) {
        Map<String, DependencyModel.PackageInfo> packages = new LinkedHashMap<>();

        for (String className : model.getAllClassNames().stream().sorted().toList()) {
            DependencyModel.ClassInfo classInfo = model.getClass(className);
            if (classInfo == null || classInfo.packageName == null || classInfo.packageName.isBlank()) {
                continue;
            }

            ensurePackageHierarchy(classInfo.packageName, packages);
            packages.get(classInfo.packageName).classNames.add(className);
        }

        return packages;
    }

    private static void ensurePackageHierarchy(String packageName,
                                               Map<String, DependencyModel.PackageInfo> packages) {
        String[] parts = packageName.split("\\.");
        String current = "";
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String parent = current;
            current = current.isEmpty() ? part : current + "." + part;

            packages.computeIfAbsent(current,
                    key -> new DependencyModel.PackageInfo(key, part));

            if (!parent.isEmpty()) {
                packages.get(parent).childPackages.add(current);
            }
        }
    }
}
