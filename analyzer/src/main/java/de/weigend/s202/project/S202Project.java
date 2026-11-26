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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;

import java.util.List;
import java.util.Map;

/**
 * Serializable project snapshot: one analysis and what the user had open on
 * it. It captures the dependencies that were read, the annotations and preview
 * cuts made on them, and the tabs — what each shows and how it was set up
 * (package depth, zoom, open packages). It does not capture where the tabs sat
 * in the window; the arrangement of the window belongs to the workbench, not
 * to one analysis.
 * <p>
 * What can be computed from the dependencies — levels, cycles, back-edges,
 * invariants — is computed when the project is loaded. Files older than
 * version 7 contain parts of it; those are not read.
 */
@JsonIgnoreProperties({"domainModel", "cycleBreakEdges", "layoutInvariantReport"})
public record S202Project(
        String format,
        int formatVersion,
        CreatedWith createdWith,
        Source source,
        DependencyModelDto dependencyModel,
        ArchitectureAnnotationsDto architectureAnnotations,
        List<CutDto> previewCuts,
        List<ViewDto> views,
        String savedAt,
        MetricMappingDto metricMapping) {

    public static final String FORMAT = "structure202-project";
    public static final int MIN_SUPPORTED_FORMAT_VERSION = 1;
    /**
     * Version 4 added {@link #previewCuts()} and {@link #views()}, version 5
     * {@link ViewDto#limitBoxWidth()}, version 6 {@link ClassInfoDto#typeUses()}
     * and {@link ViewDto#hiddenEdgeKinds()}, version 7 the annotated packages of
     * {@link DependencyModelDto}; it dropped what is computed on loading. Version 8
     * added {@link ViewDto#moves()}, {@link ViewDto#plannedPackages()},
     * {@link ViewDto#hidePlan()}, {@link ViewDto#matrix()} and {@link ViewDto#matrixOpen()},
     * version 9 {@link ClassInfoDto#externalDependencies()}, {@link ClassInfoDto#origin()} and
     * {@link ViewDto#matrixExternals()}, version 10 {@link ViewDto#physical()} and {@link ViewDto#slice()},
     * version 11 {@link MethodInfoDto#complexity()}, {@link MethodInfoDto#lines()} and {@link #metricMapping()}.
     */
    public static final int FORMAT_VERSION = 11;

    /** The application and version that wrote the file. */
    public record CreatedWith(String app, String version) {}

    /** What was analysed: the kind of input, its files, and the project root if there is one. */
    public record Source(String kind, List<String> paths, String projectRoot) {}

    /**
     * @param componentPackages packages annotated with {@code @S202Component}; files older than
     *                          version 7 do not name annotated packages
     * @param apiPackages       packages annotated with {@code @S202Api}
     * @param plainPackages     packages annotated with {@code @S202Package}
     */
    public record DependencyModelDto(
            Map<String, ClassInfoDto> classes,
            Map<String, PackageInfoDto> packages,
            List<ModuleInfoDto> modules,
            List<String> componentPackages,
            List<String> apiPackages,
            List<String> plainPackages) {}

    /**
     * @param externalDependencies classes outside the analysis the class depends on;
     *                             files older than version 9 have none
     * @param origin               the file the class was read from; files older than
     *                             version 9 do not know it
     */
    public record ClassInfoDto(
            String fullName,
            String simpleName,
            String packageName,
            boolean interfaceType,
            List<String> dependencies,
            Map<String, List<EdgeKind>> dependencyKinds,
            Map<String, MethodInfoDto> methods,
            Map<String, List<TypeUseDto>> typeUses,
            Map<String, List<EdgeKind>> externalDependencies,
            String origin) {}

    /** Attributes that do not apply are left out of the file. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TypeUseDto(
            TypeUse.Kind kind,
            String member,
            String descriptor,
            String detail) {}

    /**
     * @param complexity cyclomatic complexity, {@code null} where it was not measured:
     *                   in files older than version 11 and for code not read from Java bytecode
     * @param lines      lines with code, {@code null} where they were not counted
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MethodInfoDto(
            String name,
            String descriptor,
            Map<String, Integer> methodCalls,
            Map<String, List<String>> methodCallDescriptors,
            Integer complexity,
            Integer lines) {}

    /**
     * How the 3D view shows metrics; see {@code MetricMapping}. Names of metrics,
     * scales and palettes this version does not know are read as the default.
     *
     * @param heightPercent height of the largest value in percent of the size of the picture
     * @param color    {@code null} for the colours of 2D
     * @param colorMin {@code null} for the smallest value shown
     * @param colorMax {@code null} for the largest value shown
     */
    public record MetricMappingDto(
            String height,
            String scale,
            double heightPercent,
            String color,
            Double colorMin,
            Double colorMax,
            String palette,
            boolean reversed) {}

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

    /**
     * A class or package the user moved in a tab.
     *
     * @param container the package it was put into, the id of a planned package,
     *                  or empty for the top level
     * @param newRow    whether it got a row of its own; {@code column} then says nothing
     * @param api       put into the API area of a component
     */
    public record MoveDto(String element, String container, int row, int column, boolean newRow, boolean api) {}

    /** A package the user planned in a tab. */
    public record PlannedPackageDto(String id, String name) {}

    /** A class dependency cut in the refactoring preview. */
    public record CutDto(String from, String to) {}

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
     * @param hiddenEdgeKinds  the kinds of dependency the tab does not draw;
     *                         files older than version 6 do not have it
     * @param expandedPackages package FQN to open (true) or closed (false)
     * @param active           whether this was the tab the user worked in
     * @param moves            the moves made in the tab, in order; files older than
     *                         version 8 do not have them, nor the following
     * @param plannedPackages  the packages planned in the tab, in order
     * @param hidePlan         whether the tab shows the analyzed code instead of the plan
     * @param matrix           whether the dependency matrix of the tab is open in a tab of its own
     * @param matrixOpen       the packages that are open in the matrix
     * @param matrixExternals  whether the matrix shows the libraries used; files
     *                         older than version 9 do not have it
     * @param physical         whether the tab orders the classes by the files they
     *                         were read from; files older than version 10 do not have it
     * @param slice            the depth of a horizontal slice, 0 for none; files older
     *                         than version 10 do not have it
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
            List<String> hiddenEdgeKinds,
            double zoom,
            double horizontalScroll,
            double verticalScroll,
            Map<String, Boolean> expandedPackages,
            String selected,
            boolean active,
            List<MoveDto> moves,
            List<PlannedPackageDto> plannedPackages,
            boolean hidePlan,
            boolean matrix,
            List<String> matrixOpen,
            boolean matrixExternals,
            boolean physical,
            int slice) {}
}
