package de.weigend.s202.project;

import de.weigend.s202.analysis.invariants.InvariantFinding;
import de.weigend.s202.analysis.invariants.LayoutInvariantReport;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.ui.rendering.TangleEdgeRenderer;
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
public final class S202ProjectMapper {

    public S202Project toProject(String appVersion,
                                 S202Project.Source source,
                                 DependencyModel rawModel,
                                 DomainModel domainModel,
                                 LayoutInvariantReport invariantReport,
                                 Set<TangleEdgeRenderer.Edge> cycleBreakEdges) {
        return new S202Project(
                S202Project.FORMAT,
                S202Project.FORMAT_VERSION,
                new S202Project.CreatedWith("S202 Code Analyzer", appVersion),
                source,
                toDependencyModelDto(rawModel),
                toDomainModelDto(domainModel),
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

    public Set<TangleEdgeRenderer.Edge> toCycleBreakEdges(List<S202Project.CycleBreakEdgeDto> dtos) {
        if (dtos == null) {
            return Set.of();
        }
        return dtos.stream()
                .map(edge -> new TangleEdgeRenderer.Edge(edge.from(), edge.to()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private S202Project.DependencyModelDto toDependencyModelDto(DependencyModel model) {
        if (model == null) {
            return new S202Project.DependencyModelDto(Map.of(), Map.of());
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
        return new S202Project.DependencyModelDto(classes, packages);
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

    private List<S202Project.CycleBreakEdgeDto> toCycleBreakEdgeDtos(Set<TangleEdgeRenderer.Edge> edges) {
        if (edges == null) {
            return List.of();
        }
        return edges.stream()
                .sorted(Comparator.comparing(TangleEdgeRenderer.Edge::from)
                        .thenComparing(TangleEdgeRenderer.Edge::to))
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

    private static <T> List<T> nullToList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
