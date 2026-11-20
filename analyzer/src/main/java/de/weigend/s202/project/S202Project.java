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
 * Serializable project snapshot: one analysis and what the user had open on
 * it. It captures the analyzed data model, the annotations and preview cuts
 * made on it, and its tabs — what each shows and how it was set up (package
 * depth, zoom, open packages). It does not capture where the tabs sat in the
 * window; the arrangement of the window belongs to the workbench, not to one
 * analysis.
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
        List<CycleBreakEdgeDto> previewCuts,
        List<ViewDto> views,
        String savedAt) {

    public static final String FORMAT = "structure202-project";
    public static final int MIN_SUPPORTED_FORMAT_VERSION = 1;
    /**
     * Version 4 added {@link #previewCuts()} and {@link #views()}, version 5
     * {@link ViewDto#limitBoxWidth()}.
     */
    public static final int FORMAT_VERSION = 5;

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

    /**
     * One tab of the analysis: what it shows ({@code kind} to
     * {@code tangleContext}) and how the user left it (the rest).
     *
     * @param kind             LAYERED, COMPONENT, HEXAGONAL, SCOPE or TANGLE
     * @param label            the tab title behind the name of the analysis;
     *                         null for the layered view of the whole analysis
     * @param scope            the package the tree is limited to; null for
     *                         the whole analysis
     * @param scopeExtensions  what "Add to Scope" added to that package
     * @param tangleMembers    the members of the tangle (TANGLE only)
     * @param tangleKey        the key the tangle tab is reused under
     * @param tangleContext    whether the tangle tab shows the members' context
     * @param limitBoxWidth    whether package boxes stop at a maximum width
     *                         instead of filling their row; files older than
     *                         version 5 do not have it and read as false
     * @param expandedPackages package FQN to open (true) or closed (false)
     * @param active           whether this was the tab the user worked in
     */
    public record ViewDto(
            String kind,
            String label,
            String scope,
            List<String> scopeExtensions,
            List<String> tangleMembers,
            String tangleKey,
            boolean tangleContext,
            int packageDepth,
            boolean showDependencies,
            boolean showClassCycles,
            boolean showPackageCycles,
            boolean showViolations,
            boolean limitBoxWidth,
            double zoom,
            double horizontalScroll,
            double verticalScroll,
            Map<String, Boolean> expandedPackages,
            String selected,
            boolean active) {}

    public record LayoutInvariantReportDto(
            List<String> sourcePaths,
            int maxLevel,
            int packageCount,
            int classCount,
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
