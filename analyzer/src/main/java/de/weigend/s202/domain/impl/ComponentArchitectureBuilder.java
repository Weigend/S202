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
import de.weigend.s202.domain.architecture.Element;
import de.weigend.s202.domain.architecture.Tangle;
import de.weigend.s202.domain.architecture.Violation;
import de.weigend.s202.domain.architecture.ViolationKind;
import de.weigend.s202.reader.DependencyModel;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the component architecture projection and its policy findings from
 * the already-calculated domain model. This class deliberately does not touch
 * {@link LevelCalculator}; it only reads calculated levels and dependency
 * edges.
 */
@Singleton
public final class ComponentArchitectureBuilder implements ArchitectureStyle {

    @Override
    public ArchitectureKind kind() {
        return ArchitectureKind.COMPONENT;
    }

    @Override
    public ComponentArchitecture build(ArchitectureContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return build(context.domainModel(), context.annotations(), context.rawModel());
    }

    public ComponentArchitecture build(DomainModel domain, ArchitectureAnnotations annotations) {
        return build(domain, annotations, null);
    }

    public ComponentArchitecture build(DomainModel domain,
                                       ArchitectureAnnotations annotations,
                                       DependencyModel rawModel) {
        Objects.requireNonNull(domain, "domain cannot be null");
        ArchitectureAnnotations effectiveAnnotations =
                annotations == null ? ArchitectureAnnotations.empty() : annotations;
        ComponentApiClassifier classifier = new ComponentApiClassifier(effectiveAnnotations, rawModel);
        ModelIndex index = ModelIndex.of(domain);

        List<ComponentRoot> roots = componentRoots(index, effectiveAnnotations, classifier, rawModel);
        List<ComponentArchitecture.ComponentElement> components = new ArrayList<>();
        Map<String, Membership> membershipByClass = new LinkedHashMap<>();

        for (ComponentRoot root : roots) {
            List<CalculatedElementInfo> apiClasses = selectedApiClasses(root.rootPackageFqn(), index, classifier);
            Set<String> apiFqns = new LinkedHashSet<>();
            List<Element> api = new ArrayList<>(apiClasses.size());
            for (CalculatedElementInfo cls : apiClasses) {
                apiFqns.add(cls.fullName);
                api.add(new Element.ClassElement(cls.fullName, cls.architectureLevel, cls.localLevel));
            }
            List<List<Element>> implementationRows =
                    buildImplementationRows(root.rootPackageFqn(), index, apiFqns);
            components.add(new ComponentArchitecture.ComponentElement(
                    root.id(), root.displayName(), root.rootPackageFqn(), api, implementationRows));
            registerMembership(root.rootPackageFqn(), index, apiFqns, membershipByClass);
        }

        return new ComponentArchitectureModel(
                components,
                detectViolations(domain, membershipByClass),
                detectTangles(domain));
    }

    private List<ComponentRoot> componentRoots(ModelIndex index,
                                               ArchitectureAnnotations annotations,
                                               ComponentApiClassifier classifier,
                                               DependencyModel rawModel) {
        Map<String, ComponentRoot> roots = new LinkedHashMap<>();
        String effectiveRoot = skipTransparentPassthroughs(index);
        Set<String> plainPackages = rawModel != null ? rawModel.getPlainPackages() : Set.of();
        for (CalculatedElementInfo child : sortedChildren(index, effectiveRoot)) {
            if (!"PACKAGE".equals(child.type)) {
                continue;
            }
            if (plainPackages.contains(child.fullName)) {
                continue;
            }
            boolean hasApiClasses = !selectedApiClasses(child.fullName, index, classifier).isEmpty();
            boolean hasImplSubPackage = hasDirectImplSubPackage(child.fullName, index);
            if (hasApiClasses || hasImplSubPackage) {
                roots.put(child.fullName, new ComponentRoot(
                        child.fullName, child.simpleName, child.fullName));
            }
        }
        if (rawModel != null) {
            for (String packageFqn : rawModel.getComponentAnnotatedPackages()) {
                CalculatedElementInfo pkg = index.packages().get(packageFqn);
                if (pkg != null) {
                    roots.putIfAbsent(packageFqn, new ComponentRoot(
                            pkg.simpleName, pkg.simpleName, packageFqn));
                }
            }
        }
        for (ArchitectureAnnotations.ComponentSpec spec : annotations.components()) {
            CalculatedElementInfo pkg = index.packages().get(spec.rootPackageFqn());
            if (pkg != null) {
                roots.putIfAbsent(spec.rootPackageFqn(), new ComponentRoot(
                        spec.id(), spec.displayName(), spec.rootPackageFqn()));
            }
        }
        return List.copyOf(roots.values());
    }

    private static List<CalculatedElementInfo> selectedApiClasses(String rootPackageFqn,
                                                                  ModelIndex index,
                                                                  ComponentApiClassifier classifier) {
        List<CalculatedElementInfo> api = new ArrayList<>();
        collectSelectedApiClasses(rootPackageFqn, index, classifier, false, false, api);
        api.sort(Comparator.comparing(c -> c.fullName, String.CASE_INSENSITIVE_ORDER));
        return api;
    }

    private static void collectSelectedApiClasses(String parentFqn,
                                                  ModelIndex index,
                                                  ComponentApiClassifier classifier,
                                                  boolean inheritedApiPackage,
                                                  boolean inheritedImplementationPackage,
                                                  List<CalculatedElementInfo> api) {
        List<CalculatedElementInfo> children = sortedChildren(index, parentFqn);
        // Promote all direct classes to API if the package signals it is a component:
        // either by containing interfaces (public contract) or by having an impl sub-package
        // (explicit API/impl split). Neither signal propagates into sub-packages.
        boolean packageHasInterfaces = !inheritedImplementationPackage
                && children.stream().anyMatch(c -> "CLASS".equals(c.type) && c.interfaceType);
        boolean packageHasImplSubPackage = !inheritedImplementationPackage
                && children.stream().anyMatch(c -> "PACKAGE".equals(c.type)
                && ComponentApiClassifier.isImplementationPackageName(c.simpleName));
        boolean promoteToApi = packageHasInterfaces || packageHasImplSubPackage;
        for (CalculatedElementInfo child : children) {
            boolean inApiPackage = inheritedApiPackage
                    || ("PACKAGE".equals(child.type)
                    && (ComponentApiClassifier.isApiPackageName(child.simpleName)
                        || classifier.isApiAnnotatedPackage(child.fullName)));
            boolean inImplementationPackage = inheritedImplementationPackage
                    || ("PACKAGE".equals(child.type)
                    && ComponentApiClassifier.isImplementationPackageName(child.simpleName));
            if ("CLASS".equals(child.type)) {
                if (classifier.isSelectedApiClass(
                        child.fullName,
                        child.simpleName,
                        child.interfaceType,
                        inApiPackage || promoteToApi,
                        inImplementationPackage)) {
                    api.add(child);
                }
            } else {
                collectSelectedApiClasses(
                        child.fullName,
                        index,
                        classifier,
                        inApiPackage,
                        inImplementationPackage,
                        api);
            }
        }
    }

    private static List<List<Element>> buildImplementationRows(String rootPackageFqn,
                                                               ModelIndex index,
                                                               Set<String> apiFqns) {
        List<Element> sortedElements = new ArrayList<>();
        for (CalculatedElementInfo child : sortedChildren(index, rootPackageFqn)) {
            Element element = toImplementationElement(child, index, apiFqns);
            if (element != null) {
                sortedElements.add(element);
            }
        }
        return groupByLocalLevel(sortedElements);
    }

    private static Element toImplementationElement(CalculatedElementInfo info,
                                                   ModelIndex index,
                                                   Set<String> apiFqns) {
        if ("CLASS".equals(info.type)) {
            return apiFqns.contains(info.fullName)
                    ? null
                    : new Element.ClassElement(info.fullName, info.architectureLevel, info.localLevel);
        }

        List<Element> children = new ArrayList<>();
        for (CalculatedElementInfo child : sortedChildren(index, info.fullName)) {
            Element childElement = toImplementationElement(child, index, apiFqns);
            if (childElement != null) {
                children.add(childElement);
            }
        }
        if (children.isEmpty()) {
            return null;
        }
        return new Element.PackageElement(
                info.fullName,
                info.architectureLevel,
                info.localLevel,
                groupByLocalLevel(children));
    }

    private static List<List<Element>> groupByLocalLevel(List<Element> elements) {
        if (elements.isEmpty()) {
            return List.of();
        }
        List<List<Element>> rows = new ArrayList<>();
        List<Element> currentRow = new ArrayList<>();
        int currentLevel = Integer.MIN_VALUE;
        for (Element element : elements) {
            if (element.localLevel() != currentLevel) {
                if (!currentRow.isEmpty()) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                currentLevel = element.localLevel();
            }
            currentRow.add(element);
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }
        return rows;
    }

    private static void registerMembership(String rootPackageFqn,
                                           ModelIndex index,
                                           Set<String> apiFqns,
                                           Map<String, Membership> membershipByClass) {
        for (CalculatedElementInfo cls : index.classes().values()) {
            if (isInPackage(cls.fullName, rootPackageFqn)) {
                Membership membership = new Membership(rootPackageFqn, apiFqns.contains(cls.fullName));
                Membership previous = membershipByClass.putIfAbsent(cls.fullName, membership);
                if (previous != null && !previous.componentRootFqn().equals(rootPackageFqn)) {
                    throw new IllegalArgumentException(
                            "Class belongs to overlapping components: " + cls.fullName);
                }
            }
        }
    }

    private static List<Violation> detectViolations(DomainModel domain,
                                                    Map<String, Membership> membershipByClass) {
        List<Violation> violations = new ArrayList<>();
        for (CalculatedElementInfo source : domain.getAllClasses().values()) {
            Membership sourceMembership = membershipByClass.get(source.fullName);
            for (String dep : source.dependencies) {
                Membership targetMembership = membershipByClass.get(dep);
                if (targetMembership == null) {
                    continue;
                }
                CalculatedElementInfo target = domain.getClass(dep);
                int targetLevel = target == null ? -1 : target.architectureLevel;
                boolean sourceIsApi = sourceMembership != null && sourceMembership.api();
                boolean targetIsImplementation = !targetMembership.api();

                if (sourceIsApi && targetIsImplementation) {
                    violations.add(new Violation(
                            source.fullName,
                            dep,
                            ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION,
                            source.architectureLevel,
                            targetLevel,
                            domain.isClassBackEdge(source.fullName, dep)));
                }

                boolean foreignTarget = sourceMembership == null
                        || !sourceMembership.componentRootFqn().equals(targetMembership.componentRootFqn());
                if (foreignTarget && targetIsImplementation) {
                    violations.add(new Violation(
                            source.fullName,
                            dep,
                            ViolationKind.COMPONENT_API_BYPASS,
                            source.architectureLevel,
                            targetLevel,
                            domain.isClassBackEdge(source.fullName, dep)));
                }
            }
        }
        return violations;
    }

    private static List<Tangle> detectTangles(DomainModel domain) {
        return domain.getPackageTangles().stream()
                .map(Tangle::new)
                .toList();
    }

    private static boolean hasDirectImplSubPackage(String packageFqn, ModelIndex index) {
        return sortedChildren(index, packageFqn).stream()
                .anyMatch(c -> "PACKAGE".equals(c.type)
                        && ComponentApiClassifier.isImplementationPackageName(c.simpleName));
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

    private record ComponentRoot(String id, String displayName, String rootPackageFqn) {}

    private record Membership(String componentRootFqn, boolean api) {}

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

            Set<String> packageNames = new HashSet<>(packages.keySet());
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
