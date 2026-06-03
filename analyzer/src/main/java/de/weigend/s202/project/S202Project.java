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
package de.weigend.s202.project;

import de.weigend.s202.reader.EdgeKind;

import java.util.List;
import java.util.Map;

/**
 * Serializable project snapshot. It captures the analyzed data model, not UI
 * state such as windows, zoom, package depth, selected nodes, or open tabs.
 */
public record S202Project(
        String format,
        int formatVersion,
        CreatedWith createdWith,
        Source source,
        DependencyModelDto dependencyModel,
        DomainModelDto domainModel,
        ArchitectureAnnotationsDto architectureAnnotations,
        List<CycleBreakEdgeDto> cycleBreakEdges,
        LayoutInvariantReportDto layoutInvariantReport,
        String savedAt) {

    public static final String FORMAT = "structure202-project";
    public static final int MIN_SUPPORTED_FORMAT_VERSION = 1;
    public static final int FORMAT_VERSION = 3;

    public record CreatedWith(String app, String version) {}

    public record Source(String kind, List<String> paths, String projectRoot) {}

    public record DependencyModelDto(
            Map<String, ClassInfoDto> classes,
            Map<String, PackageInfoDto> packages,
            List<ModuleInfoDto> modules) {}

    public record ClassInfoDto(
            String fullName,
            String simpleName,
            String packageName,
            boolean interfaceType,
            List<String> dependencies,
            Map<String, List<EdgeKind>> dependencyKinds,
            Map<String, MethodInfoDto> methods) {}

    public record MethodInfoDto(
            String name,
            String descriptor,
            Map<String, Integer> methodCalls,
            Map<String, List<String>> methodCallDescriptors) {}

    public record PackageInfoDto(
            String fullName,
            String simpleName,
            List<String> childPackages,
            List<String> classNames) {}

    public record ModuleInfoDto(
            String name,
            String version,
            List<ModulePackageAccessDto> exportedPackages,
            List<ModulePackageAccessDto> openedPackages) {}

    public record ModulePackageAccessDto(
            String packageName,
            List<String> targetModules) {}

    public record DomainModelDto(
            Map<String, CalculatedElementDto> classes,
            Map<String, CalculatedElementDto> packages) {}

    public record ArchitectureAnnotationsDto(
            List<ComponentSpecDto> components,
            List<ComponentApiMarkDto> componentApiIncludes,
            List<ComponentApiMarkDto> componentApiExcludes,
            List<PortSpecDto> ports,
            List<ElementRoleMarkDto> roles) {}

    public record ComponentSpecDto(
            String id,
            String displayName,
            String rootPackageFqn) {}

    public record ComponentApiMarkDto(
            String componentId,
            String elementFqn) {}

    public record PortSpecDto(
            String id,
            String componentOrSegmentId,
            String classFqn,
            String direction) {}

    public record ElementRoleMarkDto(
            String elementFqn,
            String role) {}

    public record CalculatedElementDto(
            String fullName,
            String simpleName,
            String type,
            boolean interfaceType,
            int level,
            List<String> dependencies,
            List<String> dependents) {}

    public record CycleBreakEdgeDto(String from, String to) {}

    public record LayoutInvariantReportDto(
            List<String> sourcePaths,
            int maxLevel,
            int districtCount,
            int buildingCount,
            int dependencyCount,
            int identifiedBackEdgeCount,
            List<InvariantFindingDto> findings) {}

    public record InvariantFindingDto(
            String ruleId,
            String message,
            String fromName,
            String toName,
            int fromLevel,
            int toLevel,
            String fromContainer,
            String toContainer) {}
}
