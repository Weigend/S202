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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the hexagonal architecture projection from the already-calculated
 * domain model. The builder maps classes to radial rings and segments and then
 * classifies policy findings. It never changes architecture levels.
 */
public final class HexagonalArchitectureBuilder implements ArchitectureStyle {

    private static final List<HexagonalArchitecture.HexRing> DEFAULT_RINGS = List.of(
            new HexagonalArchitecture.HexRing("core", "Core", HexagonalArchitecture.RingRole.CORE),
            new HexagonalArchitecture.HexRing("application", "Application / Ports",
                    HexagonalArchitecture.RingRole.APPLICATION),
            new HexagonalArchitecture.HexRing("adapter", "Adapters",
                    HexagonalArchitecture.RingRole.ADAPTER));

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.HEXAGONAL;
    }

    @Override
    public HexagonalArchitecture build(ArchitectureContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return build(context.domainModel(), context.annotations(), context.rawModel());
    }

    public HexagonalArchitecture build(DomainModel domain, ArchitectureAnnotations annotations) {
        return build(domain, annotations, null);
    }

    public HexagonalArchitecture build(DomainModel domain,
                                       ArchitectureAnnotations annotations,
                                       DependencyModel rawModel) {
        Objects.requireNonNull(domain, "domain cannot be null");
        ArchitectureAnnotations effectiveAnnotations =
                annotations == null ? ArchitectureAnnotations.empty() : annotations;
        ModelIndex index = ModelIndex.of(domain);
        ComponentApiClassifier apiClassifier = new ComponentApiClassifier(effectiveAnnotations, rawModel);
        List<SegmentRoot> roots = segmentRoots(domain, index, effectiveAnnotations, rawModel);
        Map<String, SegmentRoot> segmentByClass = segmentMembership(index, roots);
        Map<String, ElementProjection> projectionByClass = new LinkedHashMap<>();
        List<HexagonalArchitecture.HexElement> elements = new ArrayList<>();
        List<HexagonalArchitecture.HexPort> ports = new ArrayList<>();

        int maxLevel = Math.max(0, domain.getMaxLevel());
        for (CalculatedElementInfo cls : sortedClasses(domain)) {
            SegmentRoot segment = segmentByClass.get(cls.fullName);
            if (segment == null) {
                continue;
            }
            boolean componentApi = isComponentApiCandidate(cls, index, apiClassifier);
            ArchitectureAnnotations.PortSpec explicitPort = effectiveAnnotations.explicitPort(cls.fullName);
            ArchitectureAnnotations.ElementRole elementRole = effectiveAnnotations.explicitElementRole(cls.fullName);
            HexagonalArchitecture.RingRole ringRole =
                    classifyRing(cls, maxLevel, elementRole, explicitPort, componentApi);
            boolean portCandidate = componentApi || explicitPort != null;
            boolean explicitPortElement = explicitPort != null;

            projectionByClass.put(cls.fullName, new ElementProjection(
                    segment.id(), ringRole, elementRole, componentApi, portCandidate, explicitPortElement));
            elements.add(new HexagonalArchitecture.HexElement(
                    cls.fullName,
                    cls.simpleName,
                    true,
                    segment.id(),
                    ringRole,
                    elementRole,
                    cls.architectureLevel,
                    cls.localLevel,
                    componentApi,
                    portCandidate,
                    explicitPortElement));
            if (explicitPort != null) {
                ports.add(new HexagonalArchitecture.HexPort(
                        explicitPort.id(),
                        explicitPort.classFqn(),
                        segment.id(),
                        explicitPort.direction(),
                        true));
            }
        }

        return new HexagonalArchitecture(
                DEFAULT_RINGS,
                roots.stream()
                        .map(root -> new HexagonalArchitecture.HexSegment(
                                root.id(), root.label(), root.rootFqn(), root.explicit()))
                        .toList(),
                ports.stream()
                        .sorted(Comparator.comparing(HexagonalArchitecture.HexPort::segmentId)
                                .thenComparing(HexagonalArchitecture.HexPort::classFqn))
                        .toList(),
                elements,
                detectViolations(domain, projectionByClass),
                detectTangles(domain));
    }

    private static List<SegmentRoot> segmentRoots(DomainModel domain,
                                                  ModelIndex index,
                                                  ArchitectureAnnotations annotations,
                                                  DependencyModel rawModel) {
        Map<String, SegmentRoot> roots = new LinkedHashMap<>();
        ComponentArchitecture componentArchitecture =
                new ComponentArchitectureBuilder().build(domain, annotations, rawModel);
        for (ComponentArchitecture.ComponentElement component : componentArchitecture.components()) {
            roots.put(component.rootPackageFqn(), new SegmentRoot(
                    component.id(),
                    component.displayName(),
                    component.rootPackageFqn(),
                    true));
        }

        String effectiveRoot = skipTransparentPassthroughs(index);
        for (CalculatedElementInfo child : sortedChildren(index, effectiveRoot)) {
            if (!"PACKAGE".equals(child.type)) {
                continue;
            }
            if (isCoveredByExistingRoot(child.fullName, roots.values())) {
                continue;
            }
            roots.put(child.fullName, new SegmentRoot(
                    child.fullName, child.simpleName, child.fullName, false));
        }
        return List.copyOf(roots.values());
    }

    private static boolean isCoveredByExistingRoot(String packageFqn, Iterable<SegmentRoot> roots) {
        for (SegmentRoot root : roots) {
            if (packageFqn.equals(root.rootFqn())
                    || packageFqn.startsWith(root.rootFqn() + ".")
                    || root.rootFqn().startsWith(packageFqn + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, SegmentRoot> segmentMembership(ModelIndex index, List<SegmentRoot> roots) {
        Map<String, SegmentRoot> membership = new LinkedHashMap<>();
        List<SegmentRoot> sortedRoots = roots.stream()
                .sorted(Comparator.comparingInt((SegmentRoot root) -> root.rootFqn().length()).reversed())
                .toList();
        for (CalculatedElementInfo cls : index.classes().values()) {
            for (SegmentRoot root : sortedRoots) {
                if (isInPackage(cls.fullName, root.rootFqn())) {
                    membership.put(cls.fullName, root);
                    break;
                }
            }
        }
        return membership;
    }

    private static HexagonalArchitecture.RingRole classifyRing(CalculatedElementInfo cls,
                                                               int maxLevel,
                                                               ArchitectureAnnotations.ElementRole role,
                                                               ArchitectureAnnotations.PortSpec explicitPort,
                                                               boolean componentApi) {
        if (role == ArchitectureAnnotations.ElementRole.CORE) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        if (role == ArchitectureAnnotations.ElementRole.ADAPTER) {
            return HexagonalArchitecture.RingRole.ADAPTER;
        }
        if (role == ArchitectureAnnotations.ElementRole.INBOUND_PORT
                || role == ArchitectureAnnotations.ElementRole.OUTBOUND_PORT
                || explicitPort != null
                || componentApi) {
            return HexagonalArchitecture.RingRole.APPLICATION;
        }
        if (maxLevel <= 0) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        double ratio = cls.architectureLevel / (double) maxLevel;
        if (ratio <= 0.34) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        if (ratio <= 0.67) {
            return HexagonalArchitecture.RingRole.APPLICATION;
        }
        return HexagonalArchitecture.RingRole.ADAPTER;
    }

    private static boolean isComponentApiCandidate(CalculatedElementInfo cls,
                                                   ModelIndex index,
                                                   ComponentApiClassifier classifier) {
        return classifier.isSelectedApiClass(
                cls.fullName,
                cls.simpleName,
                cls.interfaceType,
                isInApiPackage(cls.fullName, index));
    }

    private static boolean isInApiPackage(String classFqn, ModelIndex index) {
        String current = parentOf(classFqn);
        while (current != null && !current.isEmpty()) {
            CalculatedElementInfo pkg = index.packages().get(current);
            if (pkg != null && ComponentApiClassifier.isApiPackageName(pkg.simpleName)) {
                return true;
            }
            current = parentOf(current);
        }
        return false;
    }

    private static List<Violation> detectViolations(DomainModel domain,
                                                    Map<String, ElementProjection> projectionByClass) {
        List<Violation> violations = new ArrayList<>();
        for (CalculatedElementInfo source : sortedClasses(domain)) {
            ElementProjection sourceProjection = projectionByClass.get(source.fullName);
            if (sourceProjection == null) {
                continue;
            }
            for (String targetFqn : source.dependencies) {
                ElementProjection targetProjection = projectionByClass.get(targetFqn);
                if (targetProjection == null) {
                    continue;
                }
                CalculatedElementInfo target = domain.getClass(targetFqn);
                int targetLevel = target == null ? -1 : target.architectureLevel;
                boolean targetIsExplicitPort = targetProjection.explicitPort();
                boolean outward = ringRank(sourceProjection.ringRole()) < ringRank(targetProjection.ringRole());

                if (outward && !isAllowedOutboundPortDependency(targetProjection)) {
                    violations.add(new Violation(
                            source.fullName,
                            targetFqn,
                            ViolationKind.HEXAGON_OUTWARD_DEPENDENCY,
                            source.architectureLevel,
                            targetLevel,
                            domain.isClassBackEdge(source.fullName, targetFqn)));
                    continue;
                }

                boolean crossSegment = !sourceProjection.segmentId().equals(targetProjection.segmentId());
                boolean adapterToInnerImplementation =
                        sourceProjection.ringRole() == HexagonalArchitecture.RingRole.ADAPTER
                                && ringRank(targetProjection.ringRole())
                                <= ringRank(HexagonalArchitecture.RingRole.APPLICATION);
                if ((crossSegment || adapterToInnerImplementation) && !targetIsExplicitPort) {
                    violations.add(new Violation(
                            source.fullName,
                            targetFqn,
                            ViolationKind.HEXAGON_PORT_BYPASS,
                            source.architectureLevel,
                            targetLevel,
                            domain.isClassBackEdge(source.fullName, targetFqn)));
                }
            }
        }
        return violations;
    }

    private static boolean isAllowedOutboundPortDependency(ElementProjection targetProjection) {
        return targetProjection.explicitPort()
                && (targetProjection.elementRole() == ArchitectureAnnotations.ElementRole.OUTBOUND_PORT
                || targetProjection.elementRole() == ArchitectureAnnotations.ElementRole.INBOUND_PORT
                || targetProjection.elementRole() == ArchitectureAnnotations.ElementRole.NONE);
    }

    private static int ringRank(HexagonalArchitecture.RingRole role) {
        return switch (role) {
            case CORE -> 0;
            case APPLICATION -> 1;
            case ADAPTER -> 2;
        };
    }

    private static List<Tangle> detectTangles(DomainModel domain) {
        return domain.getPackageTangles().stream()
                .map(Tangle::new)
                .toList();
    }

    private static List<CalculatedElementInfo> sortedClasses(DomainModel domain) {
        return domain.getAllClasses().values().stream()
                .sorted(Comparator.comparing(c -> c.fullName))
                .toList();
    }

    private static String skipTransparentPassthroughs(ModelIndex index) {
        String current = "";
        while (true) {
            List<CalculatedElementInfo> subPackages = sortedChildren(index, current).stream()
                    .filter(c -> "PACKAGE".equals(c.type))
                    .toList();
            if (subPackages.size() != 1) {
                return current;
            }
            current = subPackages.get(0).fullName;
        }
    }

    private static List<CalculatedElementInfo> sortedChildren(ModelIndex index, String parentFqn) {
        List<CalculatedElementInfo> children = new ArrayList<>(
                index.contentsByParent().getOrDefault(parentFqn == null ? "" : parentFqn, List.of()));
        children.sort(Comparator
                .comparingInt((CalculatedElementInfo c) -> c.localLevel).reversed()
                .thenComparing(c -> c.fullName));
        return children;
    }

    private static boolean isInPackage(String classFqn, String packageFqn) {
        return classFqn != null
                && packageFqn != null
                && classFqn.startsWith(packageFqn + ".");
    }

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }

    private record SegmentRoot(String id, String label, String rootFqn, boolean explicit) {}

    private record ElementProjection(String segmentId,
                                     HexagonalArchitecture.RingRole ringRole,
                                     ArchitectureAnnotations.ElementRole elementRole,
                                     boolean componentApi,
                                     boolean portCandidate,
                                     boolean explicitPort) {}

    private record ModelIndex(
            Map<String, CalculatedElementInfo> classes,
            Map<String, CalculatedElementInfo> packages,
            Map<String, List<CalculatedElementInfo>> contentsByParent) {

        static ModelIndex of(DomainModel domain) {
            Map<String, CalculatedElementInfo> classes = domain.getAllClasses();
            Map<String, CalculatedElementInfo> packages = domain.getAllPackages();
            Map<String, List<CalculatedElementInfo>> contentsByParent = new HashMap<>();
            for (CalculatedElementInfo cls : classes.values()) {
                contentsByParent.computeIfAbsent(parentOf(cls.fullName), k -> new ArrayList<>()).add(cls);
            }
            for (CalculatedElementInfo pkg : packages.values()) {
                contentsByParent.computeIfAbsent(parentOf(pkg.fullName), k -> new ArrayList<>()).add(pkg);
                contentsByParent.computeIfAbsent(pkg.fullName, k -> new ArrayList<>());
            }

            Set<String> packageNames = new LinkedHashSet<>(packages.keySet());
            for (String fqn : new ArrayList<>(contentsByParent.keySet())) {
                String current = fqn;
                while (current != null && !current.isEmpty()) {
                    contentsByParent.computeIfAbsent(current, k -> new ArrayList<>());
                    int dot = current.lastIndexOf('.');
                    current = dot <= 0 ? "" : current.substring(0, dot);
                }
            }
            for (String pkg : packageNames) {
                contentsByParent.computeIfAbsent(pkg, k -> new ArrayList<>());
            }
            return new ModelIndex(classes, packages, contentsByParent);
        }
    }
}
