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
import de.weigend.s202.domain.architecture.ComponentMoves;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

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
        return build(context.domainModel(), context.annotations(), context.rawModel(), context.componentMoves());
    }

    public ComponentArchitecture build(DomainModel domain, ArchitectureAnnotations annotations) {
        return build(domain, annotations, null);
    }

    public ComponentArchitecture build(DomainModel domain,
                                       ArchitectureAnnotations annotations,
                                       DependencyModel rawModel) {
        return build(domain, annotations, rawModel, ComponentMoves.none());
    }

    /** The components of the analyzed code; the violations are those after {@code moves}. */
    public ComponentArchitecture build(DomainModel domain,
                                       ArchitectureAnnotations annotations,
                                       DependencyModel rawModel,
                                       ComponentMoves moves) {
        Objects.requireNonNull(domain, "domain cannot be null");
        ArchitectureAnnotations effectiveAnnotations =
                annotations == null ? ArchitectureAnnotations.empty() : annotations;
        ModelIndex index = ModelIndex.of(domain);
        ComponentStructure structure = ComponentStructure.of(
                index.packages().keySet(),
                skipTransparentPassthroughs(index),
                effectiveAnnotations,
                ComponentStructure.Declarations.of(rawModel));

        Map<String, List<ComponentStructure.Component>> innerComponents = new HashMap<>();
        List<ComponentStructure.Component> outermost = new ArrayList<>();
        for (ComponentStructure.Component component : structure.components()) {
            if (component.parent() == null) {
                outermost.add(component);
            } else {
                innerComponents.computeIfAbsent(component.parent().rootFqn(), k -> new ArrayList<>())
                        .add(component);
            }
        }
        Map<String, List<CalculatedElementInfo>> classesByComponent = new HashMap<>();
        for (CalculatedElementInfo cls : index.classes().values()) {
            ComponentStructure.Component component = structure.componentOf(cls.fullName);
            if (component != null) {
                classesByComponent.computeIfAbsent(component.rootFqn(), k -> new ArrayList<>()).add(cls);
            }
        }

        // Rows first, then names: the order the packages have in the other projections.
        outermost.sort(Comparator
                .comparingInt((ComponentStructure.Component c) -> localLevel(c, index)).reversed()
                .thenComparing(ComponentStructure.Component::rootFqn));
        List<ComponentArchitecture.ComponentElement> components = outermost.stream()
                .map(component -> toComponentElement(
                        component, structure, index, innerComponents, classesByComponent))
                .toList();

        return new ComponentArchitectureModel(
                components,
                detectViolations(domain, index, structure, effectiveAnnotations, rawModel, moves),
                detectTangles(domain));
    }

    private static int localLevel(ComponentStructure.Component component, ModelIndex index) {
        return index.packages().get(component.rootFqn()).localLevel;
    }

    private static ComponentArchitecture.ComponentElement toComponentElement(
            ComponentStructure.Component component,
            ComponentStructure structure,
            ModelIndex index,
            Map<String, List<ComponentStructure.Component>> innerComponents,
            Map<String, List<CalculatedElementInfo>> classesByComponent) {
        List<ComponentStructure.Component> inner =
                innerComponents.getOrDefault(component.rootFqn(), List.of());
        List<Element> api = new ArrayList<>();
        for (CalculatedElementInfo cls : classesByComponent.getOrDefault(component.rootFqn(), List.of())) {
            if (structure.isInContract(component, cls.fullName)) {
                api.add(classElement(cls));
            }
        }
        for (ComponentStructure.Component child : inner) {
            if (structure.isPackageInContract(component, child.rootFqn())) {
                api.add(wholePackage(index.packages().get(child.rootFqn()), index));
            }
        }
        api.sort(Comparator.comparing(Element::fqn, String.CASE_INSENSITIVE_ORDER));

        List<Element> implementation = new ArrayList<>();
        for (CalculatedElementInfo child : sortedChildren(index, component.rootFqn())) {
            Element element = toImplementationElement(child, component, structure, index);
            if (element != null) {
                implementation.add(element);
            }
        }
        return new ComponentArchitecture.ComponentElement(
                component.id(),
                component.displayName(),
                component.rootFqn(),
                api,
                groupByLocalLevel(implementation),
                inner.stream()
                        .map(child -> toComponentElement(
                                child, structure, index, innerComponents, classesByComponent))
                        .toList());
    }

    /** The private part of {@code info} in {@code component}, {@code null} when all of it is public. */
    private static Element toImplementationElement(CalculatedElementInfo info,
                                                   ComponentStructure.Component component,
                                                   ComponentStructure structure,
                                                   ModelIndex index) {
        if ("CLASS".equals(info.type)) {
            return structure.isInContract(component, info.fullName) ? null : classElement(info);
        }
        if (structure.componentWithRoot(info.fullName) != null) {
            return structure.isPackageInContract(component, info.fullName) ? null : wholePackage(info, index);
        }
        List<Element> children = new ArrayList<>();
        for (CalculatedElementInfo child : sortedChildren(index, info.fullName)) {
            Element childElement = toImplementationElement(child, component, structure, index);
            if (childElement != null) {
                children.add(childElement);
            }
        }
        return children.isEmpty() ? null : packageElement(info, children);
    }

    private static Element wholePackage(CalculatedElementInfo info, ModelIndex index) {
        List<Element> children = new ArrayList<>();
        for (CalculatedElementInfo child : sortedChildren(index, info.fullName)) {
            children.add("CLASS".equals(child.type) ? classElement(child) : wholePackage(child, index));
        }
        return packageElement(info, children);
    }

    private static Element classElement(CalculatedElementInfo info) {
        return new Element.ClassElement(info.fullName, info.architectureLevel, info.localLevel);
    }

    private static Element packageElement(CalculatedElementInfo info, List<Element> children) {
        return new Element.PackageElement(
                info.fullName, info.architectureLevel, info.localLevel, groupByLocalLevel(children));
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

    private static List<Violation> detectViolations(DomainModel domain,
                                                    ModelIndex index,
                                                    ComponentStructure structure,
                                                    ArchitectureAnnotations annotations,
                                                    DependencyModel rawModel,
                                                    ComponentMoves moves) {
        if (moves == null || moves.moves().isEmpty()) {
            return detectViolations(domain, structure, fqn -> fqn);
        }
        MovedComponents moved = new MovedComponents(
                structure,
                index.packages().keySet(),
                index.classes().keySet(),
                topLevelPackage(index, structure),
                annotations,
                ComponentStructure.Declarations.of(rawModel),
                moves);
        return detectViolations(domain, moved.structure(), moved::nameOf);
    }

    /** The package whose children the view shows at top level: it does not look into a component. */
    private static String topLevelPackage(ModelIndex index, ComponentStructure structure) {
        String current = skipTransparentPassthroughs(index);
        for (String fqn = current; !fqn.isEmpty(); fqn = parentOf(fqn)) {
            if (structure.componentWithRoot(fqn) != null) {
                current = parentOf(fqn);
            }
        }
        return current;
    }

    /** @param nameOf the name of an element in {@code structure} */
    private static List<Violation> detectViolations(DomainModel domain,
                                                    ComponentStructure structure,
                                                    UnaryOperator<String> nameOf) {
        List<Violation> violations = new ArrayList<>();
        for (CalculatedElementInfo source : domain.getAllClasses().values()) {
            String sourceName = nameOf.apply(source.fullName);
            ComponentStructure.Component sourceComponent = structure.componentOf(sourceName);
            boolean sourceIsApi = sourceComponent != null
                    && structure.isInContract(sourceComponent, sourceName);
            for (String dep : source.dependencies) {
                if (domain.getClass(dep) == null) {
                    continue;
                }
                boolean bypass = structure.bypassesContract(sourceName, nameOf.apply(dep));
                // An API that reaches into a foreign implementation exposes that one.
                if (structure.leaksImplementation(sourceName, nameOf.apply(dep)) || (sourceIsApi && bypass)) {
                    violations.add(violation(
                            domain, source, dep, ViolationKind.COMPONENT_API_LEAKS_IMPLEMENTATION));
                }
                if (bypass) {
                    violations.add(violation(domain, source, dep, ViolationKind.COMPONENT_API_BYPASS));
                }
            }
        }
        return violations;
    }

    private static Violation violation(DomainModel domain,
                                       CalculatedElementInfo source,
                                       String targetFqn,
                                       ViolationKind kind) {
        CalculatedElementInfo target = domain.getClass(targetFqn);
        return new Violation(
                source.fullName,
                targetFqn,
                kind,
                source.architectureLevel,
                target == null ? -1 : target.architectureLevel,
                domain.isClassBackEdge(source.fullName, targetFqn));
    }

    private static List<Tangle> detectTangles(DomainModel domain) {
        return domain.getPackageTangles().stream()
                .map(Tangle::new)
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

    private static String parentOf(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return "";
        }
        return fqn.substring(0, fqn.lastIndexOf('.'));
    }

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
