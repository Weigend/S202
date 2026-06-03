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
package de.weigend.s202.domain.architecture;

/**
 * Shared component-API classification. Manual annotations have priority;
 * naming/interface heuristics remain a fallback so existing projects get a
 * useful first projection without changing the underlying dependency model.
 */
public final class ComponentApiClassifier {

    private final ArchitectureAnnotations annotations;

    public ComponentApiClassifier(ArchitectureAnnotations annotations) {
        this.annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
    }

    public boolean isSelectedApiClass(String fqn,
                                      String simpleName,
                                      boolean interfaceType,
                                      boolean inApiPackage) {
        Boolean explicit = annotations.explicitComponentApiDecision(fqn);
        if (explicit != null) {
            return explicit;
        }
        return inApiPackage || isHeuristicApiClass(simpleName, interfaceType);
    }

    public Boolean explicitDecision(String fqn) {
        return annotations.explicitComponentApiDecision(fqn);
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
}
