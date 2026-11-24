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
 * Suggests which classes of the hexagonal view look like ports: manual API
 * marks and JPMS exports first, then package names and interfaces. These are
 * suggestions, unlike the component API, which follows {@link ComponentStructure}.
 */
final class PortCandidateClassifier {

    private final ArchitectureAnnotations annotations;
    private final Set<String> exportedPackages;

    PortCandidateClassifier(ArchitectureAnnotations annotations, DependencyModel rawModel) {
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        this.exportedPackages = rawModel == null ? Set.of() : Set.copyOf(rawModel.getExportedPackageNames());
    }

    boolean isPortCandidate(String fqn,
                            String simpleName,
                            boolean interfaceType,
                            boolean inApiPackage,
                            boolean inImplementationPackage) {
        Boolean explicit = annotations.explicitComponentApiDecision(fqn);
        if (explicit != null) {
            return explicit;
        }
        if (exportedPackages.contains(packageNameOf(fqn))) {
            return true;
        }
        if (inImplementationPackage) {
            return false;
        }
        return inApiPackage || looksLikePort(simpleName, interfaceType);
    }

    private static boolean looksLikePort(String simpleName, boolean interfaceType) {
        String name = simpleName == null ? "" : simpleName;
        return interfaceType || name.endsWith("Api") || name.endsWith("API");
    }

    static boolean isApiPackageName(String simpleName) {
        String normalized = simpleName == null ? "" : simpleName.toLowerCase();
        return normalized.equals("api")
                || normalized.equals("apis")
                || normalized.equals("port")
                || normalized.equals("ports");
    }

    static boolean isImplementationPackageName(String simpleName) {
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
