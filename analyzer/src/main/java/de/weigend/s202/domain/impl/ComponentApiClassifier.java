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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;

import java.util.Set;

/**
 * Shared component-API classification. Manual annotations have priority;
 * JPMS exports are treated as analyzed API metadata. Naming/interface
 * heuristics remain a fallback, but they do not promote classes from known
 * implementation packages unless the user marks them explicitly.
 */
final class ComponentApiClassifier {

    private final ArchitectureAnnotations annotations;
    private final Set<String> exportedPackages;
    private final Set<String> apiAnnotatedPackages;

    public ComponentApiClassifier(ArchitectureAnnotations annotations) {
        this(annotations, null);
    }

    public ComponentApiClassifier(ArchitectureAnnotations annotations, DependencyModel rawModel) {
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        this.exportedPackages = rawModel == null ? Set.of() : Set.copyOf(rawModel.getExportedPackageNames());
        this.apiAnnotatedPackages = rawModel == null ? Set.of() : Set.copyOf(rawModel.getApiAnnotatedPackages());
    }

    public boolean isApiAnnotatedPackage(String packageFqn) {
        return apiAnnotatedPackages.contains(packageFqn);
    }

    public boolean isSelectedApiClass(String fqn,
                                      String simpleName,
                                      boolean interfaceType,
                                      boolean inApiPackage) {
        return isSelectedApiClass(fqn, simpleName, interfaceType, inApiPackage, false);
    }

    public boolean isSelectedApiClass(String fqn,
                                      String simpleName,
                                      boolean interfaceType,
                                      boolean inApiPackage,
                                      boolean inImplementationPackage) {
        Boolean explicit = annotations.explicitComponentApiDecision(fqn);
        if (explicit != null) {
            return explicit;
        }
        if (isJpmsExportedClass(fqn)) {
            return true;
        }
        if (inImplementationPackage) {
            return false;
        }
        return inApiPackage || isHeuristicApiClass(simpleName, interfaceType);
    }

    public Boolean explicitDecision(String fqn) {
        return annotations.explicitComponentApiDecision(fqn);
    }

    public boolean isJpmsExportedClass(String fqn) {
        return exportedPackages.contains(packageNameOf(fqn));
    }

    public static boolean isHeuristicApiClass(String simpleName, boolean interfaceType) {
        String name = simpleName == null ? "" : simpleName;
        return interfaceType || name.endsWith("Api") || name.endsWith("API");
    }

    public static boolean isApiPackageName(String simpleName) {
        String normalized = simpleName == null ? "" : simpleName.toLowerCase();
        return normalized.equals("api")
                || normalized.equals("apis")
                || normalized.equals("port")
                || normalized.equals("ports");
    }

    public static boolean isImplementationPackageName(String simpleName) {
        String normalized = simpleName == null ? "" : simpleName.toLowerCase();
        return normalized.equals("impl")
                || normalized.equals("implementation")
                || normalized.equals("internal")
                || normalized.equals("private");
    }

    private static String packageNameOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }
}
