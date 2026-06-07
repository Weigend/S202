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
package de.weigend.s202.project.impl;

import de.weigend.s202.project.ProjectMapper;
import de.weigend.s202.project.S202Project;
import de.weigend.s202.analysis.invariants.InvariantFinding;
import jakarta.inject.Singleton;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Converts between mutable runtime models and the stable serializable project
 * schema. The DTO is intentionally separate from rendering and shell state.
 */
@Singleton
public final class S202ProjectMapper implements ProjectMapper {

    public S202Project toProject(String appVersion,
                                 S202Project.Source source,
                                 DependencyModel rawModel,
                                 DomainModel domainModel,
                                 LayoutInvariantReport invariantReport,
                                 Set<DependencyEdge> cycleBreakEdges) {
        return toProject(appVersion, source, rawModel, domainModel,
                ArchitectureAnnotations.empty(), invariantReport, cycleBreakEdges);
    }

    public S202Project toProject(String appVersion,
                                 S202Project.Source source,
                                 DependencyModel rawModel,
                                 DomainModel domainModel,
                                 ArchitectureAnnotations annotations,
                                 LayoutInvariantReport invariantReport,
                                 Set<DependencyEdge> cycleBreakEdges) {
        return new S202Project(
                S202Project.FORMAT,
                S202Project.FORMAT_VERSION,
                new S202Project.CreatedWith("S202 Code Analyzer", appVersion),
                source,
                toDependencyModelDto(rawModel),
                toDomainModelDto(domainModel),
                toArchitectureAnnotationsDto(annotations),
                toCycleBreakEdgeDtos(cycleBreakEdges),
                toLayoutInvariantReportDto(invariantReport),
                Instant.now().toString());
    }

    public DependencyModel toDependencyModel(S202Project.DependencyModelDto dto) {
        DependencyModel model = new DependencyModel();
        if (dto == null) {
            return model;
        }
        if (dto.classes() != null) {
            for (S202Project.ClassInfoDto classDto : dto.classes().values()) {
                DependencyModel.ClassInfo classInfo = new DependencyModel.ClassInfo(
                        classDto.fullName(), classDto.simpleName(), classDto.packageName(), classDto.interfaceType());
                if (classDto.dependencies() != null) {
                    classInfo.dependencies.addAll(classDto.dependencies());
                }
                if (classDto.dependencyKinds() != null && !classDto.dependencyKinds().isEmpty()) {
                    for (Map.Entry<String, List<EdgeKind>> dep : classDto.dependencyKinds().entrySet()) {
                        for (EdgeKind kind : dep.getValue()) {
                            classInfo.addDependency(dep.getKey(), kind);
                        }
                    }
                }
                if (classDto.methods() != null) {
                    for (S202Project.MethodInfoDto methodDto : classDto.methods().values()) {
                        DependencyModel.MethodInfo method = new DependencyModel.MethodInfo(
                                methodDto.name(), methodDto.descriptor());
                        if (methodDto.methodCalls() != null) {
                            method.methodCalls.putAll(methodDto.methodCalls());
                        }
                        if (methodDto.methodCallDescriptors() != null) {
                            for (Map.Entry<String, List<String>> call : methodDto.methodCallDescriptors().entrySet()) {
                                method.methodCallDescriptors.put(call.getKey(), new HashSet<>(call.getValue()));
                            }
                        }
                        classInfo.methods.put(method.name + method.descriptor, method);
                    }
                }
                model.addClass(classInfo.fullName, classInfo);
            }
        }
        Map<String, DependencyModel.PackageInfo> packages = new LinkedHashMap<>();
        if (dto.packages() != null) {
            for (S202Project.PackageInfoDto packageDto : dto.packages().values()) {
                DependencyModel.PackageInfo packageInfo = new DependencyModel.PackageInfo(
                        packageDto.fullName(), packageDto.simpleName());
                if (packageDto.childPackages() != null) {
                    packageInfo.childPackages.addAll(packageDto.childPackages());
                }
                if (packageDto.classNames() != null) {
                    packageInfo.classNames.addAll(packageDto.classNames());
                }
                packages.put(packageInfo.fullName, packageInfo);
            }
        }
        model.setPackages(packages);
        if (dto.modules() != null) {
            for (S202Project.ModuleInfoDto moduleDto : dto.modules()) {
                if (moduleDto.name() == null || moduleDto.name().isBlank()) {
                    continue;
                }
                DependencyModel.ModuleInfo moduleInfo = new DependencyModel.ModuleInfo(
                        moduleDto.name(), moduleDto.version());
                for (S202Project.ModulePackageAccessDto access : nullToList(moduleDto.exportedPackages())) {
                    if (access.packageName() != null && !access.packageName().isBlank()) {
                        moduleInfo.addExportedPackage(access.packageName(), nullToList(access.targetModules()));
                    }
                }
                for (S202Project.ModulePackageAccessDto access : nullToList(moduleDto.openedPackages())) {
                    if (access.packageName() != null && !access.packageName().isBlank()) {
                        moduleInfo.addOpenedPackage(access.packageName(), nullToList(access.targetModules()));
                    }
                }
                model.addModule(moduleInfo);
            }
        }
        return model;
    }

    public DomainModel toDomainModel(S202Project.DomainModelDto dto) {
        DomainModel model = new DomainModel();
        if (dto == null) {
            return model;
        }
        if (dto.classes() != null) {
            for (S202Project.CalculatedElementDto element : dto.classes().values()) {
                model.addClass(element.fullName(), toCalculatedElementInfo(element));
            }
        }
        if (dto.packages() != null) {
            for (S202Project.CalculatedElementDto element : dto.packages().values()) {
                model.addPackage(element.fullName(), toCalculatedElementInfo(element));
            }
        }
        return model;
    }

    public ArchitectureAnnotations toArchitectureAnnotations(S202Project.ArchitectureAnnotationsDto dto) {
        if (dto == null) {
            return ArchitectureAnnotations.empty();
        }
        List<ArchitectureAnnotations.ComponentSpec> components = dto.components() == null ? List.of()
                : dto.components().stream()
                        .filter(c -> c.id() != null && c.rootPackageFqn() != null)
                        .map(c -> new ArchitectureAnnotations.ComponentSpec(
                                c.id(), c.displayName(), c.rootPackageFqn()))
                        .toList();
        Set<String> includes = toComponentApiMarkSet(dto.componentApiIncludes());
        Set<String> excludes = toComponentApiMarkSet(dto.componentApiExcludes());
        List<ArchitectureAnnotations.PortSpec> ports = dto.ports() == null ? List.of()
                : dto.ports().stream()
                        .filter(p -> p.id() != null && p.classFqn() != null)
                        .map(p -> new ArchitectureAnnotations.PortSpec(
                                p.id(),
                                p.componentOrSegmentId(),
                                p.classFqn(),
                                parsePortDirection(p.direction())))
                        .toList();
        List<ArchitectureAnnotations.ElementRoleMark> roles = dto.roles() == null ? List.of()
                : dto.roles().stream()
                        .filter(r -> r.elementFqn() != null)
                        .map(r -> new ArchitectureAnnotations.ElementRoleMark(
                                r.elementFqn(),
                                parseElementRole(r.role())))
                        .toList();
        return new ArchitectureAnnotations(components, includes, excludes, ports, roles);
    }

    public LayoutInvariantReport toLayoutInvariantReport(S202Project.LayoutInvariantReportDto dto) {
        if (dto == null) {
            return null;
        }
        List<InvariantFinding> findings = dto.findings() == null ? List.of()
                : dto.findings().stream()
                        .map(f -> new InvariantFinding(
                                f.ruleId(), f.message(), f.fromName(), f.toName(),
                                f.fromLevel(), f.toLevel(), f.fromContainer(), f.toContainer()))
                        .toList();
        return new LayoutInvariantReport(
                nullToList(dto.sourcePaths()),
                dto.maxLevel(),
                dto.districtCount(),
                dto.buildingCount(),
                dto.dependencyCount(),
                dto.identifiedBackEdgeCount(),
                findings);
    }

    public Set<DependencyEdge> toCycleBreakEdges(List<S202Project.CycleBreakEdgeDto> dtos) {
        if (dtos == null) {
            return Set.of();
        }
        return dtos.stream()
                .map(edge -> new DependencyEdge(edge.from(), edge.to()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private S202Project.DependencyModelDto toDependencyModelDto(DependencyModel model) {
        if (model == null) {
            return new S202Project.DependencyModelDto(Map.of(), Map.of(), List.of());
        }
        Map<String, S202Project.ClassInfoDto> classes = new TreeMap<>();
        for (Map.Entry<String, DependencyModel.ClassInfo> entry : model.getAllClasses().entrySet()) {
            DependencyModel.ClassInfo info = entry.getValue();
            classes.put(entry.getKey(), new S202Project.ClassInfoDto(
                    info.fullName,
                    info.simpleName,
                    info.packageName,
                    info.interfaceType,
                    sorted(info.dependencies),
                    toDependencyKindsDto(info.dependencyKinds),
                    toMethodsDto(info.methods)));
        }

        Map<String, S202Project.PackageInfoDto> packages = new TreeMap<>();
        for (Map.Entry<String, DependencyModel.PackageInfo> entry : model.getAllPackages().entrySet()) {
            DependencyModel.PackageInfo info = entry.getValue();
            packages.put(entry.getKey(), new S202Project.PackageInfoDto(
                    info.fullName,
                    info.simpleName,
                    sorted(info.childPackages),
                    sorted(info.classNames)));
        }
        return new S202Project.DependencyModelDto(classes, packages, toModuleInfoDtos(model));
    }

    private List<S202Project.ModuleInfoDto> toModuleInfoDtos(DependencyModel model) {
        if (model == null || model.getAllModules().isEmpty()) {
            return List.of();
        }
        return model.getAllModules().values().stream()
                .sorted(Comparator.comparing(module -> module.name))
                .map(module -> new S202Project.ModuleInfoDto(
                        module.name,
                        module.version,
                        toModulePackageAccessDtos(module.exportedPackages),
                        toModulePackageAccessDtos(module.openedPackages)))
                .toList();
    }

    private List<S202Project.ModulePackageAccessDto> toModulePackageAccessDtos(
            Set<DependencyModel.ModulePackageAccess> accessSet) {
        if (accessSet == null || accessSet.isEmpty()) {
            return List.of();
        }
        return accessSet.stream()
                .sorted(Comparator
                        .comparing(DependencyModel.ModulePackageAccess::packageName)
                        .thenComparing(access -> String.join(",", sorted(access.targetModules()))))
                .map(access -> new S202Project.ModulePackageAccessDto(
                        access.packageName(),
                        sorted(access.targetModules())))
                .toList();
    }

    private S202Project.DomainModelDto toDomainModelDto(DomainModel model) {
        if (model == null) {
            return new S202Project.DomainModelDto(Map.of(), Map.of());
        }
        Map<String, S202Project.CalculatedElementDto> classes = new TreeMap<>();
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> entry : model.getAllClasses().entrySet()) {
            classes.put(entry.getKey(), toCalculatedElementDto(entry.getValue()));
        }
        Map<String, S202Project.CalculatedElementDto> packages = new TreeMap<>();
        for (Map.Entry<String, DomainModel.CalculatedElementInfo> entry : model.getAllPackages().entrySet()) {
            packages.put(entry.getKey(), toCalculatedElementDto(entry.getValue()));
        }
        return new S202Project.DomainModelDto(classes, packages);
    }

    private S202Project.ArchitectureAnnotationsDto toArchitectureAnnotationsDto(
            ArchitectureAnnotations annotations) {
        ArchitectureAnnotations effective =
                annotations == null ? ArchitectureAnnotations.empty() : annotations;
        return new S202Project.ArchitectureAnnotationsDto(
                effective.components().stream()
                        .sorted(Comparator.comparing(ArchitectureAnnotations.ComponentSpec::id))
                        .map(c -> new S202Project.ComponentSpecDto(
                                c.id(), c.displayName(), c.rootPackageFqn()))
                        .toList(),
                toComponentApiMarkDtos(effective.componentApiIncludes()),
                toComponentApiMarkDtos(effective.componentApiExcludes()),
                effective.ports().stream()
                        .sorted(Comparator.comparing(ArchitectureAnnotations.PortSpec::id))
                        .map(p -> new S202Project.PortSpecDto(
                                p.id(),
                                p.componentOrSegmentId(),
                                p.classFqn(),
                                p.direction().name()))
                        .toList(),
                effective.roles().stream()
                        .sorted(Comparator.comparing(ArchitectureAnnotations.ElementRoleMark::elementFqn))
                        .map(r -> new S202Project.ElementRoleMarkDto(
                                r.elementFqn(),
                                r.role().name()))
                        .toList());
    }

    private Map<String, List<EdgeKind>> toDependencyKindsDto(Map<String, EnumSet<EdgeKind>> dependencyKinds) {
        Map<String, List<EdgeKind>> result = new TreeMap<>();
        if (dependencyKinds == null) {
            return result;
        }
        for (Map.Entry<String, EnumSet<EdgeKind>> entry : dependencyKinds.entrySet()) {
            List<EdgeKind> kinds = new ArrayList<>(entry.getValue());
            kinds.sort(Comparator.comparing(Enum::name));
            result.put(entry.getKey(), kinds);
        }
        return result;
    }

    private Map<String, S202Project.MethodInfoDto> toMethodsDto(Map<String, DependencyModel.MethodInfo> methods) {
        Map<String, S202Project.MethodInfoDto> result = new TreeMap<>();
        if (methods == null) {
            return result;
        }
        for (Map.Entry<String, DependencyModel.MethodInfo> entry : methods.entrySet()) {
            DependencyModel.MethodInfo info = entry.getValue();
            result.put(entry.getKey(), new S202Project.MethodInfoDto(
                    info.name,
                    info.descriptor,
                    new TreeMap<>(info.methodCalls),
                    toMethodCallDescriptorsDto(info.methodCallDescriptors)));
        }
        return result;
    }

    private Map<String, List<String>> toMethodCallDescriptorsDto(Map<String, Set<String>> descriptors) {
        Map<String, List<String>> result = new TreeMap<>();
        if (descriptors == null) {
            return result;
        }
        for (Map.Entry<String, Set<String>> entry : descriptors.entrySet()) {
            result.put(entry.getKey(), sorted(entry.getValue()));
        }
        return result;
    }

    private S202Project.CalculatedElementDto toCalculatedElementDto(DomainModel.CalculatedElementInfo info) {
        return new S202Project.CalculatedElementDto(
                info.fullName,
                info.simpleName,
                info.type,
                info.interfaceType,
                info.architectureLevel,
                sorted(info.dependencies),
                sorted(info.dependents));
    }

    private DomainModel.CalculatedElementInfo toCalculatedElementInfo(S202Project.CalculatedElementDto element) {
        DomainModel.CalculatedElementInfo info = new DomainModel.CalculatedElementInfo(
                element.fullName(),
                element.simpleName(),
                element.type(),
                element.level(),
                new HashSet<>(nullToList(element.dependencies())),
                element.interfaceType());
        for (String dependent : nullToList(element.dependents())) {
            info.addDependent(dependent);
        }
        return info;
    }

    private List<S202Project.CycleBreakEdgeDto> toCycleBreakEdgeDtos(Set<DependencyEdge> edges) {
        if (edges == null) {
            return List.of();
        }
        return edges.stream()
                .sorted(Comparator.comparing(DependencyEdge::from)
                        .thenComparing(DependencyEdge::to))
                .map(edge -> new S202Project.CycleBreakEdgeDto(edge.from(), edge.to()))
                .toList();
    }

    private S202Project.LayoutInvariantReportDto toLayoutInvariantReportDto(LayoutInvariantReport report) {
        if (report == null) {
            return null;
        }
        return new S202Project.LayoutInvariantReportDto(
                report.sourcePaths(),
                report.maxLevel(),
                report.districtCount(),
                report.buildingCount(),
                report.dependencyCount(),
                report.identifiedBackEdgeCount(),
                report.findings().stream()
                        .map(f -> new S202Project.InvariantFindingDto(
                                f.ruleId(), f.message(), f.fromName(), f.toName(),
                                f.fromLevel(), f.toLevel(), f.fromContainer(), f.toContainer()))
                        .toList());
    }

    private static List<String> sorted(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().sorted().toList();
    }

    private static List<S202Project.ComponentApiMarkDto> toComponentApiMarkDtos(Set<String> fqns) {
        if (fqns == null || fqns.isEmpty()) {
            return List.of();
        }
        return fqns.stream()
                .sorted()
                .map(fqn -> new S202Project.ComponentApiMarkDto(null, fqn))
                .toList();
    }

    private static Set<String> toComponentApiMarkSet(List<S202Project.ComponentApiMarkDto> marks) {
        if (marks == null || marks.isEmpty()) {
            return Set.of();
        }
        return marks.stream()
                .map(S202Project.ComponentApiMarkDto::elementFqn)
                .filter(fqn -> fqn != null && !fqn.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static ArchitectureAnnotations.PortDirection parsePortDirection(String value) {
        if (value == null || value.isBlank()) {
            return ArchitectureAnnotations.PortDirection.GENERIC;
        }
        try {
            return ArchitectureAnnotations.PortDirection.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return ArchitectureAnnotations.PortDirection.GENERIC;
        }
    }

    private static ArchitectureAnnotations.ElementRole parseElementRole(String value) {
        if (value == null || value.isBlank()) {
            return ArchitectureAnnotations.ElementRole.NONE;
        }
        try {
            return ArchitectureAnnotations.ElementRole.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return ArchitectureAnnotations.ElementRole.NONE;
        }
    }

    private static <T> List<T> nullToList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
