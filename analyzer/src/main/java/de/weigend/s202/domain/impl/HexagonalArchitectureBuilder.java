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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.DomainModel.CalculatedElementInfo;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureContext;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ArchitectureStyle;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.HexagonalArchitecture;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import jakarta.inject.Singleton;

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
 * domain model. Ring assignment happens at PACKAGE granularity: each package
 * that directly contains classes is projected as a package element whose ring
 * derives from the package architectureLevel (explicit annotations win), and
 * classes inherit the ring of their package. Only explicit port/role
 * annotations override the inherited ring for individual classes. The builder
 * never changes architecture levels.
 */
@Singleton
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

        Map<String, PackageProjection> packagesByFqn = projectPackages(index, effectiveAnnotations, rawModel);

        // Segments are the BUSINESS THEMES: the largest sibling group of core
        // packages under one common parent (e.g. the four children of the
        // domain package: book, publisher, inventory, logistics). Grouping by
        // parent keeps contract packages like api/spi — whose interfaces sit
        // at level 0 by design — from being mistaken for themes. Every class
        // is assigned to one theme; the theme sector spans all rings. When the
        // model has no recognizable themes, fall back to top-level segments.
        Map<String, List<String>> coreLeavesByParent = new java.util.TreeMap<>();
        for (PackageProjection pkg : packagesByFqn.values()) {
            if (pkg.ringRole() == HexagonalArchitecture.RingRole.CORE) {
                coreLeavesByParent.computeIfAbsent(parentOf(pkg.fqn()), k -> new ArrayList<>()).add(pkg.fqn());
            }
        }
        List<String> themes = coreLeavesByParent.values().stream()
                .max(Comparator.comparingInt(List::size))
                .map(group -> group.stream().sorted().toList())
                .orElse(List.of());
        boolean themeMode = themes.size() >= 2;

        List<HexagonalArchitecture.HexSegment> segments;
        Map<String, String> segmentIdByClass;
        if (themeMode) {
            segments = themes.stream()
                    .map(theme -> new HexagonalArchitecture.HexSegment(
                            theme,
                            packagesByFqn.get(theme).simpleName(),
                            theme,
                            false))
                    .toList();
            segmentIdByClass = assignClassesToThemes(domain, themes);
        } else {
            List<SegmentRoot> roots = segmentRoots(domain, index, effectiveAnnotations, rawModel);
            Map<String, SegmentRoot> segmentByClass = segmentMembership(index, roots);
            segments = roots.stream()
                    .map(root -> new HexagonalArchitecture.HexSegment(
                            root.id(), root.label(), root.rootFqn(), root.explicit()))
                    .toList();
            segmentIdByClass = new LinkedHashMap<>();
            segmentByClass.forEach((fqn, root) -> segmentIdByClass.put(fqn, root.id()));
        }

        Map<String, ElementProjection> projectionByClass = new LinkedHashMap<>();
        List<HexagonalArchitecture.HexElement> elements = new ArrayList<>();
        List<HexagonalArchitecture.HexPort> ports = new ArrayList<>();

        for (CalculatedElementInfo cls : sortedClasses(domain)) {
            String segmentId = segmentIdByClass.get(cls.fullName);
            if (segmentId == null) {
                continue;
            }
            boolean componentApi = isComponentApiCandidate(cls, index, apiClassifier);
            ArchitectureAnnotations.PortSpec explicitPort = effectiveAnnotations.explicitPort(cls.fullName);
            ArchitectureAnnotations.ElementRole elementRole = effectiveAnnotations.explicitElementRole(cls.fullName);
            PackageProjection parentPackage = packagesByFqn.get(parentOf(cls.fullName));
            HexagonalArchitecture.RingRole ringRole =
                    ringForClass(elementRole, explicitPort, parentPackage);
            boolean portCandidate = componentApi || explicitPort != null;
            boolean explicitPortElement = explicitPort != null;

            projectionByClass.put(cls.fullName, new ElementProjection(
                    segmentId, ringRole, elementRole, componentApi, portCandidate, explicitPortElement));
            elements.add(new HexagonalArchitecture.HexElement(
                    cls.fullName,
                    cls.simpleName,
                    true,
                    segmentId,
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
                        segmentId,
                        explicitPort.direction(),
                        true));
            }
        }

        // Package elements carry the ring of each package; their segment is the
        // majority theme of their classes (a package like application.api may
        // contribute classes to several sectors).
        for (PackageProjection pkg : packagesByFqn.values()) {
            String segmentId = packageSegment(pkg.fqn(), index, segmentIdByClass);
            if (segmentId == null) {
                continue;
            }
            elements.add(new HexagonalArchitecture.HexElement(
                    pkg.fqn(),
                    pkg.simpleName(),
                    false,
                    segmentId,
                    pkg.ringRole(),
                    pkg.elementRole(),
                    pkg.architectureLevel(),
                    pkg.localLevel(),
                    false,
                    false,
                    false));
        }

        return new HexagonalArchitectureModel(
                DEFAULT_RINGS,
                segments,
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

    /**
     * Projects every package that directly contains at least one class. The
     * package ring derives from the package architectureLevel relative to the
     * maximum projected package level. Precedence: an explicit ElementRole
     * annotation on the package FQN wins, then the contract signal (see
     * {@link #contractSignal}), then the level bucket.
     */
    private static Map<String, PackageProjection> projectPackages(ModelIndex index,
                                                                  ArchitectureAnnotations annotations,
                                                                  DependencyModel rawModel) {
        java.util.SortedSet<String> packageFqns = new java.util.TreeSet<>();
        for (CalculatedElementInfo cls : index.classes().values()) {
            String parent = parentOf(cls.fullName);
            if (!parent.isEmpty()) {
                packageFqns.add(parent);
            }
        }

        int maxPackageLevel = 0;
        for (String packageFqn : packageFqns) {
            CalculatedElementInfo pkg = index.packages().get(packageFqn);
            if (pkg != null) {
                maxPackageLevel = Math.max(maxPackageLevel, pkg.architectureLevel);
            }
        }

        Map<String, ContractSide> contractSides = contractSides(index, annotations);

        Map<String, PackageProjection> result = new LinkedHashMap<>();
        for (String packageFqn : packageFqns) {
            CalculatedElementInfo pkg = index.packages().get(packageFqn);
            int architectureLevel = pkg == null ? 0 : pkg.architectureLevel;
            int localLevel = pkg == null ? 0 : pkg.localLevel;
            String simpleName = pkg == null
                    ? packageFqn.substring(packageFqn.lastIndexOf('.') + 1)
                    : pkg.simpleName;
            ArchitectureAnnotations.ElementRole role = annotations.explicitElementRole(packageFqn);
            HexagonalArchitecture.RingRole signal =
                    contractSignal(packageFqn, index, rawModel, contractSides);
            result.put(packageFqn, new PackageProjection(
                    packageFqn,
                    simpleName,
                    classifyPackageRing(role, signal, architectureLevel, maxPackageLevel),
                    role,
                    architectureLevel,
                    localLevel));
        }
        return result;
    }

    /**
     * Maps every contract class (explicit port or member of a conventionally
     * named api/spi package) to its side of the hexagon.
     */
    private static Map<String, ContractSide> contractSides(ModelIndex index,
                                                           ArchitectureAnnotations annotations) {
        Map<String, ContractSide> sides = new HashMap<>();
        for (CalculatedElementInfo cls : index.classes().values()) {
            ArchitectureAnnotations.PortSpec port = annotations.explicitPort(cls.fullName);
            if (port != null) {
                sides.put(cls.fullName,
                        port.direction() == ArchitectureAnnotations.PortDirection.OUTBOUND
                                ? ContractSide.SPI
                                : ContractSide.API);
                continue;
            }
            String parent = parentOf(cls.fullName);
            String packageName = parent.substring(parent.lastIndexOf('.') + 1);
            if ("spi".equalsIgnoreCase(packageName)) {
                sides.put(cls.fullName, ContractSide.SPI);
            } else if ("api".equalsIgnoreCase(packageName)) {
                sides.put(cls.fullName, ContractSide.API);
            }
        }
        return sides;
    }

    /**
     * Ring signal from how a package relates to the hexagon's contracts.
     * Levels alone cannot separate the service layer from the adapters — both
     * sit one step above the ports — but the edge kinds can:
     *
     * <pre>
     * implements an SPI contract -> driven adapter (persistence, carrier)
     * implements an API contract -> the application implementation
     * only USES API contracts    -> driving adapter (rest, ui) or glue
     * </pre>
     *
     * Returns null when the package touches no contracts (or no raw model with
     * edge kinds is available) — the level bucket decides then. Contract
     * packages themselves are exempt.
     */
    private static HexagonalArchitecture.RingRole contractSignal(String packageFqn,
                                                                 ModelIndex index,
                                                                 DependencyModel rawModel,
                                                                 Map<String, ContractSide> contractSides) {
        if (rawModel == null) {
            return null;
        }
        String packageName = packageFqn.substring(packageFqn.lastIndexOf('.') + 1);
        if ("api".equalsIgnoreCase(packageName) || "spi".equalsIgnoreCase(packageName)) {
            return null;
        }

        boolean implementsSpi = false;
        boolean implementsApi = false;
        boolean usesApi = false;
        for (CalculatedElementInfo element : index.contentsByParent().getOrDefault(packageFqn, List.of())) {
            if ("PACKAGE".equals(element.type)) {
                continue;
            }
            DependencyModel.ClassInfo raw = rawModel.getAllClasses().get(element.fullName);
            if (raw == null) {
                continue;
            }
            for (Map.Entry<String, java.util.EnumSet<EdgeKind>> dep : raw.dependencyKinds.entrySet()) {
                ContractSide side = contractSides.get(dep.getKey());
                if (side == null) {
                    continue;
                }
                boolean realizes = dep.getValue().contains(EdgeKind.IMPLEMENTS)
                        || dep.getValue().contains(EdgeKind.EXTENDS);
                if (realizes && side == ContractSide.SPI) {
                    implementsSpi = true;
                } else if (realizes) {
                    implementsApi = true;
                } else if (side == ContractSide.API) {
                    usesApi = true;
                }
            }
        }
        if (implementsSpi) {
            return HexagonalArchitecture.RingRole.ADAPTER;
        }
        if (implementsApi) {
            return HexagonalArchitecture.RingRole.APPLICATION;
        }
        if (usesApi) {
            return HexagonalArchitecture.RingRole.ADAPTER;
        }
        return null;
    }

    /**
     * Assigns every class to one business theme. Classes inside a theme
     * package belong to it directly. Everything else is assigned in phases:
     *
     * <p>Phase A — dependency voting (synchronous rounds): assigned
     * dependencies vote for their theme, each vote weighted by
     * 1/popularity of the target so that ubiquitous value objects (an Isbn
     * used everywhere) cannot drag whole adapters into their theme.
     *
     * <p>Phase B — dependent voting for classes with no dependency path to a
     * theme (contract interfaces carry no bytecode dependencies by design):
     * assigned users and implementors vote, each weighted by 1/fan-out of the
     * voter. A focused implementation (two dependencies) speaks loudly for its
     * theme; a composition root touching everything barely whispers — a cheap
     * but effective proxy for the implements relation.
     *
     * <p>Phase C — leftovers go to the largest theme so the projection stays
     * total. All iteration orders are sorted, so the result is deterministic;
     * ties resolve alphabetically.
     */
    private static Map<String, String> assignClassesToThemes(DomainModel domain, List<String> themes) {
        List<CalculatedElementInfo> classes = sortedClasses(domain);
        List<String> themesByLength = themes.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();

        Map<String, String> assignment = new LinkedHashMap<>();
        for (CalculatedElementInfo cls : classes) {
            for (String theme : themesByLength) {
                if (isInPackage(cls.fullName, theme)) {
                    assignment.put(cls.fullName, theme);
                    break;
                }
            }
        }

        Map<String, java.util.SortedSet<String>> dependentsByClass = new HashMap<>();
        for (CalculatedElementInfo cls : classes) {
            for (String target : cls.dependencies) {
                dependentsByClass.computeIfAbsent(target, k -> new java.util.TreeSet<>()).add(cls.fullName);
            }
        }
        Map<String, CalculatedElementInfo> classesByFqn = new HashMap<>();
        classes.forEach(cls -> classesByFqn.put(cls.fullName, cls));

        // Phase A: dependency votes, weighted by 1/popularity of the target.
        runVotingRounds(classes, assignment, cls -> {
            Map<String, Double> votes = new java.util.TreeMap<>();
            for (String dep : cls.dependencies.stream().sorted().toList()) {
                String theme = assignment.get(dep);
                if (theme != null) {
                    int popularity = dependentsByClass.getOrDefault(dep, java.util.Collections.emptySortedSet()).size();
                    votes.merge(theme, 1.0 / Math.max(1, popularity), Double::sum);
                }
            }
            return votes;
        });

        // Phase B: dependent votes, weighted by 1/fan-out of the voter.
        runVotingRounds(classes, assignment, cls -> {
            Map<String, Double> votes = new java.util.TreeMap<>();
            for (String dependent : dependentsByClass.getOrDefault(cls.fullName, java.util.Collections.emptySortedSet())) {
                String theme = assignment.get(dependent);
                if (theme != null) {
                    CalculatedElementInfo voter = classesByFqn.get(dependent);
                    int fanOut = voter == null ? 1 : voter.dependencies.size();
                    votes.merge(theme, 1.0 / Math.max(1, fanOut), Double::sum);
                }
            }
            return votes;
        });

        // Phase C: leftovers.
        Map<String, Double> themeSizes = new java.util.TreeMap<>();
        assignment.values().forEach(theme -> themeSizes.merge(theme, 1.0, Double::sum));
        String largestTheme = themeSizes.isEmpty() ? themes.get(0) : majorityVote(themeSizes);
        for (CalculatedElementInfo cls : classes) {
            assignment.putIfAbsent(cls.fullName, largestTheme);
        }
        return assignment;
    }

    /**
     * Synchronous voting rounds: every round evaluates all unassigned classes
     * against the assignment snapshot of the previous round, so iteration
     * order cannot leak into the result.
     */
    private static void runVotingRounds(List<CalculatedElementInfo> classes,
                                        Map<String, String> assignment,
                                        java.util.function.Function<CalculatedElementInfo, Map<String, Double>> voteFn) {
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ < classes.size()) {
            changed = false;
            Map<String, String> newlyAssigned = new LinkedHashMap<>();
            for (CalculatedElementInfo cls : classes) {
                if (assignment.containsKey(cls.fullName)) {
                    continue;
                }
                Map<String, Double> votes = voteFn.apply(cls);
                if (!votes.isEmpty()) {
                    newlyAssigned.put(cls.fullName, majorityVote(votes));
                }
            }
            if (!newlyAssigned.isEmpty()) {
                assignment.putAll(newlyAssigned);
                changed = true;
            }
        }
    }

    /** Highest vote weight wins; ties resolve to the alphabetically first key. */
    private static String majorityVote(Map<String, Double> votes) {
        String winner = null;
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : votes.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                winner = entry.getKey();
            }
        }
        return winner;
    }

    /** Majority theme of the classes directly inside the package. */
    private static String packageSegment(String packageFqn,
                                         ModelIndex index,
                                         Map<String, String> segmentIdByClass) {
        Map<String, Double> votes = new java.util.TreeMap<>();
        for (CalculatedElementInfo element : index.contentsByParent().getOrDefault(packageFqn, List.of())) {
            if (!"PACKAGE".equals(element.type)) {
                String segmentId = segmentIdByClass.get(element.fullName);
                if (segmentId != null) {
                    votes.merge(segmentId, 1.0, Double::sum);
                }
            }
        }
        return votes.isEmpty() ? null : majorityVote(votes);
    }

    private static HexagonalArchitecture.RingRole classifyPackageRing(ArchitectureAnnotations.ElementRole role,
                                                                      HexagonalArchitecture.RingRole contractSignal,
                                                                      int packageLevel,
                                                                      int maxPackageLevel) {
        if (role == ArchitectureAnnotations.ElementRole.CORE) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        if (role == ArchitectureAnnotations.ElementRole.ADAPTER) {
            return HexagonalArchitecture.RingRole.ADAPTER;
        }
        if (role == ArchitectureAnnotations.ElementRole.INBOUND_PORT
                || role == ArchitectureAnnotations.ElementRole.OUTBOUND_PORT) {
            return HexagonalArchitecture.RingRole.APPLICATION;
        }
        if (contractSignal != null) {
            return contractSignal;
        }
        if (maxPackageLevel <= 0) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        double ratio = packageLevel / (double) maxPackageLevel;
        if (ratio <= 0.34) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        if (ratio <= 0.67) {
            return HexagonalArchitecture.RingRole.APPLICATION;
        }
        return HexagonalArchitecture.RingRole.ADAPTER;
    }

    /**
     * Classes inherit the ring of their package. Only explicit class-level
     * annotations (role or port) override the inherited ring; ports sit on the
     * application boundary by definition.
     */
    private static HexagonalArchitecture.RingRole ringForClass(ArchitectureAnnotations.ElementRole role,
                                                               ArchitectureAnnotations.PortSpec explicitPort,
                                                               PackageProjection parentPackage) {
        if (role == ArchitectureAnnotations.ElementRole.CORE) {
            return HexagonalArchitecture.RingRole.CORE;
        }
        if (role == ArchitectureAnnotations.ElementRole.ADAPTER) {
            return HexagonalArchitecture.RingRole.ADAPTER;
        }
        if (role == ArchitectureAnnotations.ElementRole.INBOUND_PORT
                || role == ArchitectureAnnotations.ElementRole.OUTBOUND_PORT
                || explicitPort != null) {
            return HexagonalArchitecture.RingRole.APPLICATION;
        }
        return parentPackage == null
                ? HexagonalArchitecture.RingRole.APPLICATION
                : parentPackage.ringRole();
    }

    private static boolean isComponentApiCandidate(CalculatedElementInfo cls,
                                                   ModelIndex index,
                                                   ComponentApiClassifier classifier) {
        return classifier.isSelectedApiClass(
                cls.fullName,
                cls.simpleName,
                cls.interfaceType,
                isInApiPackage(cls.fullName, index),
                isInImplementationPackage(cls.fullName, index));
    }

    private static boolean isInApiPackage(String classFqn, ModelIndex index) {
        return isInPackageNamed(classFqn, index, true);
    }

    private static boolean isInImplementationPackage(String classFqn, ModelIndex index) {
        return isInPackageNamed(classFqn, index, false);
    }

    private static boolean isInPackageNamed(String classFqn, ModelIndex index, boolean apiPackage) {
        String current = parentOf(classFqn);
        while (current != null && !current.isEmpty()) {
            CalculatedElementInfo pkg = index.packages().get(current);
            if (pkg != null && (apiPackage
                    ? ComponentApiClassifier.isApiPackageName(pkg.simpleName)
                    : ComponentApiClassifier.isImplementationPackageName(pkg.simpleName))) {
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

                // Segments are business themes, so crossing a segment boundary is
                // normal inside the core (book -> publisher). A bypass is an
                // adapter reaching past the ports into application/core code.
                boolean adapterToInnerImplementation =
                        sourceProjection.ringRole() == HexagonalArchitecture.RingRole.ADAPTER
                                && ringRank(targetProjection.ringRole())
                                <= ringRank(HexagonalArchitecture.RingRole.APPLICATION);
                if (adapterToInnerImplementation && !targetIsExplicitPort) {
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

    private enum ContractSide {
        API,
        SPI
    }

    private record SegmentRoot(String id, String label, String rootFqn, boolean explicit) {}

    private record PackageProjection(String fqn,
                                     String simpleName,
                                     HexagonalArchitecture.RingRole ringRole,
                                     ArchitectureAnnotations.ElementRole elementRole,
                                     int architectureLevel,
                                     int localLevel) {}

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
