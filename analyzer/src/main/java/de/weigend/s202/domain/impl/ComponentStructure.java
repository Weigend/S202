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

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The component rule of S202: which packages are components and which of their
 * classes are public.
 *
 * <p>A package is a component when it has a <em>contract package</em> — a direct
 * sub-package named {@code api} (called by others), {@code spi} (implemented by
 * others), {@code port} or {@code ports} (either). Everything below the contract packages is public, everything else is
 * private. Whether a type is an interface or how a class is named plays no role.
 *
 * <p>The rule is recursive: a component may contain further components. An inner
 * component belongs to the private part of the outer one, so its contract is
 * visible inside the outer component only. Nothing nests inside a contract package
 * by convention — what is in a contract package is public as a whole.
 *
 * <p>What is declared explicitly complements the convention or overrides it, the
 * first match decides:
 * <ol>
 *   <li>the marks set by the user ({@link ArchitectureAnnotations}); a mark belongs
 *       to the innermost component around the marked element,</li>
 *   <li>JPMS exports, which are public across all enclosing components,</li>
 *   <li>{@code @S202Api} packages: part of the contract of the innermost component
 *       around them — or, on a component root, the classes of exactly this package,</li>
 *   <li>the contract packages, see {@link ContractPackages}.</li>
 * </ol>
 * {@code @S202Component} declares a component root, {@code @S202Package} prevents one.
 */
final class ComponentStructure {

    /** A component; {@code parent} is the component around it, {@code null} for an outermost one. */
    record Component(String id, String displayName, String rootFqn, Component parent) {

        boolean contains(String classFqn) {
            return isBelow(classFqn, rootFqn);
        }
    }

    /**
     * What the code declares about its packages, by package name.
     *
     * @param plain         {@code @S202Package}
     * @param components    {@code @S202Component}
     * @param apiPackages   {@code @S202Api}
     * @param exported      JPMS exports
     */
    record Declarations(Set<String> plain, Set<String> components, Set<String> apiPackages, Set<String> exported) {

        static Declarations of(DependencyModel rawModel) {
            return rawModel == null
                    ? new Declarations(Set.of(), Set.of(), Set.of(), Set.of())
                    : new Declarations(
                            rawModel.getPlainPackages(),
                            rawModel.getComponentAnnotatedPackages(),
                            rawModel.getApiAnnotatedPackages(),
                            Set.copyOf(rawModel.getExportedPackageNames()));
        }
    }

    private final Map<String, Component> componentsByRoot;
    private final Map<String, Map<String, Boolean>> marksByComponent = new HashMap<>();
    private final Map<String, Set<String>> apiPackagesByComponent = new HashMap<>();
    private final Set<String> exportedPackages;

    private ComponentStructure(Map<String, Component> componentsByRoot,
                               ArchitectureAnnotations annotations,
                               Set<String> exportedPackages,
                               Set<String> apiAnnotatedPackages) {
        this.componentsByRoot = componentsByRoot;
        this.exportedPackages = exportedPackages;
        annotations.componentApiIncludes().forEach(fqn -> assignMark(fqn, true));
        annotations.componentApiExcludes().forEach(fqn -> assignMark(fqn, false));
        for (String packageFqn : apiAnnotatedPackages) {
            Component owner = componentsByRoot.containsKey(packageFqn)
                    ? componentsByRoot.get(packageFqn)
                    : innermostAround(packageFqn);
            if (owner != null) {
                apiPackagesByComponent.computeIfAbsent(owner.rootFqn(), k -> new HashSet<>()).add(packageFqn);
            }
        }
    }

    /**
     * @param packages      all package names of the model
     * @param effectiveRoot the package below the transparent name prefix; declared API outside
     *                      of any other component makes its child there a component
     */
    static ComponentStructure of(Set<String> packages,
                                 String effectiveRoot,
                                 ArchitectureAnnotations annotations,
                                 Declarations declarations) {
        Set<String> plain = declarations.plain();
        Set<String> declaredRoots = declarations.components();
        Set<String> apiAnnotated = declarations.apiPackages();
        Set<String> exported = declarations.exported();

        Map<String, ArchitectureAnnotations.ComponentSpec> specs = new HashMap<>();
        annotations.components().forEach(spec -> specs.putIfAbsent(spec.rootPackageFqn(), spec));

        Set<String> roots = new HashSet<>();
        declaredRoots.stream().filter(packages::contains).forEach(roots::add);
        specs.keySet().stream().filter(packages::contains).forEach(roots::add);

        Set<String> contracts = new HashSet<>(apiAnnotated);
        contracts.removeAll(roots);
        for (String packageFqn : outermostFirst(packages)) {
            String parent = parentOf(packageFqn);
            if (ContractPackages.isContractName(simpleName(packageFqn))
                    && packages.contains(parent)
                    && !plain.contains(parent)
                    && !isInAny(parent, contracts)) {
                contracts.add(packageFqn);
                roots.add(parent);
            }
        }
        for (String packageFqn : outermostFirst(apiAnnotated)) {
            String parent = parentOf(packageFqn);
            if (packages.contains(packageFqn) && !isInAny(packageFqn, roots)
                    && packages.contains(parent) && !plain.contains(parent)) {
                roots.add(parent);
            }
        }
        List<String> declaredApi = new ArrayList<>(exported);
        declaredApi.addAll(annotations.componentApiIncludes());
        for (String fqn : outermostFirst(declaredApi)) {
            if (isInAny(fqn, roots)) {
                continue;
            }
            String root = childOnPath(effectiveRoot, fqn);
            if (root != null && packages.contains(root) && !plain.contains(root)) {
                roots.add(root);
            }
        }

        Map<String, Component> components = new LinkedHashMap<>();
        for (String root : outermostFirst(roots)) {
            ArchitectureAnnotations.ComponentSpec spec = specs.get(root);
            components.put(root, new Component(
                    spec == null ? root : spec.id(),
                    spec == null ? simpleName(root) : spec.displayName(),
                    root,
                    innermostAround(root, components)));
        }
        return new ComponentStructure(components, annotations, exported, apiAnnotated);
    }

    /**
     * The same structure where {@code marks} decide about the given elements in
     * the component around them. An element outside of any component is not affected.
     */
    ComponentStructure withMarks(Map<String, Boolean> marks) {
        ComponentStructure marked = new ComponentStructure(
                componentsByRoot, ArchitectureAnnotations.empty(), exportedPackages, Set.of());
        marksByComponent.forEach((root, own) -> marked.marksByComponent.put(root, new HashMap<>(own)));
        marked.apiPackagesByComponent.putAll(apiPackagesByComponent);
        marks.forEach((fqn, include) -> {
            Component owner = innermostAround(fqn);
            if (owner != null) {
                marked.marksByComponent.computeIfAbsent(owner.rootFqn(), k -> new HashMap<>()).put(fqn, include);
            }
        });
        return marked;
    }

    /** All components, every component after the one around it. */
    Collection<Component> components() {
        return componentsByRoot.values();
    }

    Component componentWithRoot(String packageFqn) {
        return componentsByRoot.get(packageFqn);
    }

    /** The innermost component a class belongs to, {@code null} when it is in none. */
    Component componentOf(String classFqn) {
        return innermostAround(classFqn);
    }

    /** Whether code outside of {@code component} may use the class as far as {@code component} is concerned. */
    boolean isInContract(Component component, String classFqn) {
        return isInContract(component, classFqn, parentOf(classFqn), true);
    }

    /** Whether a whole package — the root of an inner component — is part of the contract. */
    boolean isPackageInContract(Component component, String packageFqn) {
        return isInContract(component, packageFqn, packageFqn, false);
    }

    /**
     * Whether a class is public in {@code component}: it passes the contract of
     * {@code component} and of every inner component around it.
     */
    boolean isPublic(Component component, String classFqn) {
        for (Component c = componentOf(classFqn); c != null; c = c.parent()) {
            if (!isInContract(c, classFqn)) {
                return false;
            }
            if (c.rootFqn().equals(component.rootFqn())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code source} enters a component through something other than its contract. */
    boolean bypassesContract(String source, String target) {
        for (Component c = componentOf(target); c != null && !c.contains(source); c = c.parent()) {
            if (!isInContract(c, target)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code source} is public in a component that hides {@code target} as private. */
    boolean leaksImplementation(String source, String target) {
        for (Component c = componentOf(source); c != null && isInContract(c, source); c = c.parent()) {
            if (c.contains(target) && !isPublic(c, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInContract(Component component, String fqn, String packageFqn, boolean classElement) {
        Boolean mark = nearestMark(component, fqn);
        if (mark != null) {
            return mark;
        }
        if (classElement && exportedPackages.contains(packageFqn)) {
            return true;
        }
        String root = component.rootFqn();
        for (String apiPackage : apiPackagesByComponent.getOrDefault(root, Set.of())) {
            boolean match = apiPackage.equals(root)
                    ? classElement && packageFqn.equals(root)
                    : packageFqn.equals(apiPackage) || isBelow(packageFqn, apiPackage);
            if (match) {
                return true;
            }
        }
        String contract = childOnPath(root, packageFqn);
        return contract != null && ContractPackages.isContractName(simpleName(contract));
    }

    private Boolean nearestMark(Component component, String fqn) {
        Map<String, Boolean> marks = marksByComponent.get(component.rootFqn());
        if (marks == null) {
            return null;
        }
        for (String current = fqn; !current.isEmpty(); current = parentOf(current)) {
            Boolean mark = marks.get(current);
            if (mark != null || current.equals(component.rootFqn())) {
                return mark;
            }
        }
        return null;
    }

    private void assignMark(String fqn, boolean include) {
        Component owner = innermostAround(fqn);
        if (owner == null) {
            owner = componentsByRoot.get(fqn);
        }
        if (owner != null) {
            marksByComponent.computeIfAbsent(owner.rootFqn(), k -> new HashMap<>()).put(fqn, include);
        }
    }

    private Component innermostAround(String fqn) {
        return innermostAround(fqn, componentsByRoot);
    }

    private static Component innermostAround(String fqn, Map<String, Component> components) {
        for (String current = parentOf(fqn); !current.isEmpty(); current = parentOf(current)) {
            Component component = components.get(current);
            if (component != null) {
                return component;
            }
        }
        return null;
    }

    private static List<String> outermostFirst(Collection<String> names) {
        return names.stream()
                .sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static boolean isInAny(String fqn, Set<String> packages) {
        return packages.stream().anyMatch(pkg -> fqn.equals(pkg) || isBelow(fqn, pkg));
    }

    /** The direct child of {@code ancestor} on the way to {@code fqn}, {@code null} when there is none. */
    private static String childOnPath(String ancestor, String fqn) {
        int start = 0;
        if (!ancestor.isEmpty()) {
            if (!isBelow(fqn, ancestor)) {
                return null;
            }
            start = ancestor.length() + 1;
        }
        int dot = fqn.indexOf('.', start);
        return dot < 0 ? fqn : fqn.substring(0, dot);
    }

    private static boolean isBelow(String fqn, String packageFqn) {
        return fqn.startsWith(packageFqn + ".");
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
