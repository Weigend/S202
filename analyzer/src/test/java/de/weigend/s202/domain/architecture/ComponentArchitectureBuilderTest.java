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
import de.weigend.s202.domain.impl.ComponentArchitectureBuilder;
import de.weigend.s202.reader.DependencyModel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentArchitectureBuilderTest {

    @Test
    void apiAndSpiArePublicAndTheRestIsPrivate() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.payment", "com.acme.payment.api", "com.acme.payment.api.events",
                "com.acme.payment.spi", "com.acme.payment.store", "com.acme.util");
        cls(domain, "com.acme.payment.PaymentModule", false, 0, Set.of());
        cls(domain, "com.acme.payment.api.PaymentDto", false, 0, Set.of());
        cls(domain, "com.acme.payment.api.events.Paid", false, 0, Set.of());
        cls(domain, "com.acme.payment.spi.Gateway", true, 0, Set.of());
        // neither being an interface nor its name makes a class public
        cls(domain, "com.acme.payment.store.Store", true, 0, Set.of());
        cls(domain, "com.acme.payment.store.StoreApi", false, 0, Set.of());
        cls(domain, "com.acme.util.Strings", true, 0, Set.of());

        ComponentArchitecture architecture = build(domain);

        assertEquals(List.of("com.acme.payment"), roots(architecture.components()),
                "a package without api or spi is not a component, even with interfaces");
        assertEquals(Set.of(
                "com.acme.payment.api.PaymentDto",
                "com.acme.payment.api.events.Paid",
                "com.acme.payment.spi.Gateway"), apiFqns(architecture, "com.acme.payment"));
        assertEquals(Set.of(
                "com.acme.payment.PaymentModule",
                "com.acme.payment.store.Store",
                "com.acme.payment.store.StoreApi"), implementationClasses(architecture, "com.acme.payment"));
    }

    @Test
    void spiAloneMakesAComponent() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.importer", "com.acme.importer.spi", "com.acme.importer.csv",
                "com.acme.util");
        cls(domain, "com.acme.importer.spi.Format", true, 0, Set.of());
        cls(domain, "com.acme.importer.csv.CsvFormat", false, 0, Set.of());

        assertEquals(Set.of("com.acme.importer.spi.Format"), apiFqns(build(domain), "com.acme.importer"));
    }

    @Test
    void portAndPortsArePublicLikeApiAndSpi() {
        for (String name : List.of("port", "ports")) {
            String contract = "com.acme.order." + name;
            DomainModel domain = new DomainModel();
            pkgs(domain, "com", "com.acme", "com.acme.order", contract, contract + ".in", contract + ".out",
                    contract + ".out.api", "com.acme.order.service", "com.acme.web", "com.acme.util");
            cls(domain, contract + ".in.PlaceOrder", true, 0, Set.of());
            cls(domain, contract + ".out.Orders", true, 0, Set.of());
            cls(domain, contract + ".out.api.Paging", false, 0, Set.of());
            cls(domain, "com.acme.order.service.OrderService", false, 0, Set.of(contract + ".in.PlaceOrder"));
            cls(domain, "com.acme.web.Controller", false, 0,
                    Set.of(contract + ".in.PlaceOrder", "com.acme.order.service.OrderService"));

            ComponentArchitecture architecture = build(domain);

            assertEquals(List.of("com.acme.order"), roots(architecture.components()),
                    "nothing nests inside a contract package");
            assertEquals(Set.of(contract + ".in.PlaceOrder", contract + ".out.Orders", contract + ".out.api.Paging"),
                    apiFqns(architecture, "com.acme.order"));
            assertEquals(Set.of("BYPASS com.acme.web.Controller -> com.acme.order.service.OrderService"),
                    violations(architecture));
        }
    }

    @Test
    void accessPastTheContractIsABypassAndAnApiThatUsesTheImplementationLeaksIt() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.payment", "com.acme.payment.api", "com.acme.payment.store",
                "com.acme.shipping", "com.acme.shipping.api", "com.acme.web");
        cls(domain, "com.acme.payment.api.Payment", true, 0, Set.of("com.acme.payment.store.Store"));
        cls(domain, "com.acme.payment.store.Store", false, 0, Set.of("com.acme.payment.api.Payment"));
        cls(domain, "com.acme.shipping.api.Shipping", true, 0, Set.of("com.acme.payment.store.Store"));
        cls(domain, "com.acme.shipping.Shipper", false, 0, Set.of("com.acme.payment.api.Payment"));
        cls(domain, "com.acme.web.Controller", false, 0,
                Set.of("com.acme.payment.api.Payment", "com.acme.payment.store.Store"));

        assertEquals(Set.of(
                "LEAKS com.acme.payment.api.Payment -> com.acme.payment.store.Store",
                "BYPASS com.acme.shipping.api.Shipping -> com.acme.payment.store.Store",
                "LEAKS com.acme.shipping.api.Shipping -> com.acme.payment.store.Store",
                "BYPASS com.acme.web.Controller -> com.acme.payment.store.Store"),
                violations(build(domain)));
    }

    @Test
    void componentsNestToAnyDepth() {
        ComponentArchitecture architecture = build(nestedModel());

        assertEquals(List.of("com.acme.repo"), roots(architecture.components()));
        ComponentArchitecture.ComponentElement repo = architecture.components().get(0);
        assertEquals(List.of("com.acme.repo.adapter.solr"), roots(repo.components()),
                "the plain package adapter in between does not matter");
        ComponentArchitecture.ComponentElement solr = repo.components().get(0);
        assertEquals(List.of("com.acme.repo.adapter.solr.embedded"), roots(solr.components()));
        assertEquals(List.of("com.acme.repo", "com.acme.repo.adapter.solr", "com.acme.repo.adapter.solr.embedded"),
                roots(architecture.allComponents()));

        assertEquals(Set.of("com.acme.repo.api.Repo"), apiFqns(architecture, "com.acme.repo"),
                "the api of an inner component is private in the outer one");
        assertEquals(Set.of("com.acme.repo.adapter.solr.api.Solr"),
                apiFqns(architecture, "com.acme.repo.adapter.solr"));
        assertTrue(implementationClasses(architecture, "com.acme.repo").containsAll(Set.of(
                "com.acme.repo.adapter.solr.api.Solr", "com.acme.repo.adapter.solr.SolrClient")),
                "an inner component is part of the body with all of its content");
    }

    @Test
    void everyBoundaryOnTheWayInIsChecked() {
        assertEquals(Set.of(
                // outside: the api of an inner component is as private as the rest of repo
                "BYPASS com.acme.web.Controller -> com.acme.repo.adapter.solr.api.Solr",
                "BYPASS com.acme.web.Controller -> com.acme.repo.RepoService",
                // inside repo: its neighbours reach solr through its api only
                "BYPASS com.acme.repo.RepoService -> com.acme.repo.adapter.solr.SolrClient",
                // two boundaries at once
                "BYPASS com.acme.repo.RepoService -> com.acme.repo.adapter.solr.embedded.api.Embedded",
                // repo's api must not show what only repo may see
                "LEAKS com.acme.repo.api.Repo -> com.acme.repo.adapter.solr.api.Solr"),
                violations(build(nestedModel())));
    }

    @Test
    void aContractPackageIsPublicAsAWhole() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.log", "com.acme.log.api", "com.acme.log.api.spi",
                "com.acme.log.api.format", "com.acme.log.api.format.api", "com.acme.util");
        cls(domain, "com.acme.log.api.Logger", true, 0, Set.of());
        cls(domain, "com.acme.log.api.spi.Provider", true, 0, Set.of());
        cls(domain, "com.acme.log.api.format.Format", true, 0, Set.of());
        cls(domain, "com.acme.log.api.format.api.Layout", true, 0, Set.of());

        ComponentArchitecture architecture = build(domain);

        assertEquals(List.of("com.acme.log"), roots(architecture.allComponents()));
        assertEquals(4, apiFqns(architecture, "com.acme.log").size());
    }

    @Test
    void aComponentInsideAPlainPackageIsAnOutermostOne() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.toolkit", "com.acme.toolkit.tree", "com.acme.toolkit.tree.api",
                "com.acme.toolkit.chart", "com.acme.web");
        cls(domain, "com.acme.toolkit.tree.api.Tree", true, 0, Set.of());
        cls(domain, "com.acme.toolkit.tree.TreeModel", false, 0, Set.of());
        cls(domain, "com.acme.toolkit.chart.Chart", false, 0, Set.of());
        cls(domain, "com.acme.web.Controller", false, 0, Set.of(
                "com.acme.toolkit.tree.api.Tree", "com.acme.toolkit.tree.TreeModel", "com.acme.toolkit.chart.Chart"));

        ComponentArchitecture architecture = build(domain);

        assertEquals(List.of("com.acme.toolkit.tree"), roots(architecture.components()));
        assertEquals(Set.of("BYPASS com.acme.web.Controller -> com.acme.toolkit.tree.TreeModel"),
                violations(architecture));
    }

    @Test
    void manualMarksOverrideTheConvention() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.payment", "com.acme.payment.api", "com.acme.payment.store",
                "com.acme.util");
        cls(domain, "com.acme.payment.api.Payment", true, 0, Set.of());
        cls(domain, "com.acme.payment.api.Internal", true, 0, Set.of());
        cls(domain, "com.acme.payment.store.Store", false, 0, Set.of());

        ComponentArchitecture architecture = new ComponentArchitectureBuilder().build(domain,
                ArchitectureAnnotations.empty()
                        .withComponentApiIncluded("com.acme.payment.store")
                        .withComponentApiExcluded("com.acme.payment.api.Internal"));

        assertEquals(Set.of("com.acme.payment.api.Payment", "com.acme.payment.store.Store"),
                apiFqns(architecture, "com.acme.payment"));
    }

    @Test
    void aManualMarkBelongsToTheInnermostComponentAroundTheMarkedElement() {
        ComponentArchitecture marksClass = new ComponentArchitectureBuilder().build(nestedModel(),
                ArchitectureAnnotations.empty().withComponentApiIncluded("com.acme.repo.adapter.solr.SolrClient"));
        assertEquals(Set.of("com.acme.repo.adapter.solr.SolrClient", "com.acme.repo.adapter.solr.api.Solr"),
                apiFqns(marksClass, "com.acme.repo.adapter.solr"));
        assertEquals(Set.of("com.acme.repo.api.Repo"), apiFqns(marksClass, "com.acme.repo"));

        // Marking an inner component publishes it in the outer one — with its own api, not its internals.
        ComponentArchitecture marksComponent = new ComponentArchitectureBuilder().build(nestedModel(),
                ArchitectureAnnotations.empty().withComponentApiIncluded("com.acme.repo.adapter.solr"));
        assertEquals(Set.of("com.acme.repo.api.Repo", "com.acme.repo.adapter.solr"),
                apiFqns(marksComponent, "com.acme.repo"));
        assertEquals(Set.of("com.acme.repo.adapter.solr.api.Solr"),
                apiFqns(marksComponent, "com.acme.repo.adapter.solr"));
        assertFalse(violations(marksComponent)
                .contains("BYPASS com.acme.web.Controller -> com.acme.repo.adapter.solr.api.Solr"));
        assertEquals(Set.of("com.acme.repo.RepoService"), implementationClasses(marksComponent, "com.acme.repo"));
    }

    @Test
    void manualIncludeMakesAPackageWithoutContractAComponent() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.payment", "com.acme.payment.impl", "com.acme.util");
        cls(domain, "com.acme.payment.PaymentFacade", false, 0, Set.of());
        cls(domain, "com.acme.payment.impl.PaymentService", true, 0, Set.of());
        assertTrue(build(domain).components().isEmpty(), "impl alone is no contract");

        ComponentArchitecture architecture = new ComponentArchitectureBuilder().build(domain,
                ArchitectureAnnotations.empty().withComponentApiIncluded("com.acme.payment.PaymentFacade"));

        assertEquals(List.of("com.acme.payment"), roots(architecture.components()));
        assertEquals(Set.of("com.acme.payment.PaymentFacade"), apiFqns(architecture, "com.acme.payment"));
    }

    @Test
    void jpmsExportsMarkExactPackageClassesAsApi() {
        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(new ArchitectureContext(jpmsRawModel(), jpmsDomainModel(), ArchitectureAnnotations.empty()));

        assertEquals(Set.of("com.acme.payment.contract.PaymentFacade"), apiFqns(architecture, "com.acme.payment"));
    }

    @Test
    void s202ApiAnnotationPromotesSubPackageClassesToApi() {
        DomainModel domain = new DomainModel();
        pkg(domain, "com", 0);
        pkg(domain, "com.acme", 0);
        pkg(domain, "com.acme.payment", 1);
        pkg(domain, "com.acme.payment.contract", 1);
        pkg(domain, "com.acme.payment.impl", 0);
        pkg(domain, "com.acme.catalog", 1);
        // contract is annotated @S202Api — its classes become API even without interfaces
        cls(domain, "com.acme.payment.contract.PaymentFacade", false, 1, Set.of());
        cls(domain, "com.acme.payment.impl.PaymentService", false, 1, Set.of());
        cls(domain, "com.acme.catalog.CatalogService", false, 1, Set.of());

        DependencyModel rawModel = new DependencyModel();
        rawModel.addApiAnnotatedPackage("com.acme.payment.contract");

        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(new ArchitectureContext(rawModel, domain, ArchitectureAnnotations.empty()));

        Set<String> apiFqns = apiFqns(architecture, "com.acme.payment");
        assertTrue(apiFqns.contains("com.acme.payment.contract.PaymentFacade"),
                "@S202Api sub-package class should be promoted to API");
        assertFalse(apiFqns.contains("com.acme.payment.impl.PaymentService"),
                "impl class must not be API");
    }

    @Test
    void s202ApiOnTheRootPackageMakesExactlyThisPackagePublic() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.payment", "com.acme.payment.impl", "com.acme.util");
        cls(domain, "com.acme.payment.PaymentFacade", false, 0, Set.of());
        cls(domain, "com.acme.payment.impl.PaymentService", true, 0, Set.of());
        DependencyModel rawModel = new DependencyModel();
        rawModel.addComponentAnnotatedPackage("com.acme.payment");
        assertEquals(Set.of(), apiFqns(build(domain, rawModel), "com.acme.payment"));

        rawModel.addApiAnnotatedPackage("com.acme.payment");

        assertEquals(Set.of("com.acme.payment.PaymentFacade"), apiFqns(build(domain, rawModel), "com.acme.payment"));
    }

    @Test
    void s202PackageAnnotationPreventsComponentDetection() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.ui", "com.acme.ui.spi", "com.acme.payment", "com.acme.payment.api");
        cls(domain, "com.acme.ui.spi.View", true, 0, Set.of());
        cls(domain, "com.acme.payment.api.Payment", true, 0, Set.of());
        DependencyModel rawModel = new DependencyModel();
        rawModel.addPlainPackage("com.acme.ui");

        assertEquals(List.of("com.acme.payment"), roots(build(domain, rawModel).allComponents()));
    }

    @Test
    void manualApiExcludesOverrideJpmsExports() {
        DomainModel domain = jpmsDomainModel();
        cls(domain, "com.acme.payment.contract.PaymentDto", false, 1, Set.of());
        DependencyModel rawModel = jpmsRawModel();
        ArchitectureAnnotations annotations = new ArchitectureAnnotations(
                List.of(),
                Set.of(),
                Set.of("com.acme.payment.contract.PaymentFacade"),
                List.of(),
                List.of());

        ComponentArchitecture architecture = new ComponentArchitectureBuilder()
                .build(new ArchitectureContext(rawModel, domain, annotations));

        Set<String> apiFqns = apiFqns(architecture, "com.acme.payment");
        assertFalse(apiFqns.contains("com.acme.payment.contract.PaymentFacade"));
        assertTrue(apiFqns.contains("com.acme.payment.contract.PaymentDto"));
    }

    /* ----- what-if moves ------------------------------------------------------ */

    @Test
    void movesChangeTheViolationsButNotTheComponents() {
        ComponentArchitecture moved = moved(move("com.acme.web.Controller", "com.acme.repo", false));

        assertEquals(Set.of(), bypassesOf(moved, "com.acme.web.Controller", "com.acme.repo.RepoService"),
                "inside repo it may use what is private there");
        assertEquals(apiFqns(build(nestedModel()), "com.acme.repo"), apiFqns(moved, "com.acme.repo"));
        assertEquals(implementationClasses(build(nestedModel()), "com.acme.repo"),
                implementationClasses(moved, "com.acme.repo"));
    }

    @Test
    void aMovedClassIsWhereItWasDropped() {
        // private class of repo, dropped into the api of the inner component: solr's code may use it ...
        ComponentArchitecture api = moved(move("com.acme.repo.RepoService", "com.acme.repo.adapter.solr", true));
        assertEquals(Set.of(), bypassesOf(api, "com.acme.repo.adapter.solr.SolrClient", "com.acme.repo.RepoService"));
        // ... and it may use solr's internals, but not those of the component inside
        assertEquals(Set.of(), bypassesOf(api, "com.acme.repo.RepoService", "com.acme.repo.adapter.solr.SolrClient"));
        assertTrue(violations(api).contains(
                "LEAKS com.acme.repo.RepoService -> com.acme.repo.adapter.solr.SolrClient"));

        // out of the component into a plain package: free to use, but a stranger to repo
        ComponentArchitecture out = moved(move("com.acme.repo.RepoService", "com.acme.web", false));
        assertEquals(Set.of(), bypassesOf(out, "com.acme.web.Controller", "com.acme.repo.RepoService"));
        assertEquals(Set.of("BYPASS com.acme.repo.RepoService -> com.acme.repo.adapter.solr.api.Solr"),
                bypassesOf(out, "com.acme.repo.RepoService", "com.acme.repo.adapter.solr.api.Solr"));
    }

    @Test
    void theLastMoveCountsAndMovingBackRestoresTheViolations() {
        ComponentArchitecture back = moved(
                move("com.acme.web.Controller", "com.acme.repo", false),
                move("com.acme.web.Controller", "com.acme.web", false));

        assertEquals(violations(build(nestedModel())), violations(back));
    }

    @Test
    void aMoveInsideItsComponentKeepsTheSideOfTheElement() {
        // an api class shown in the api package stays public, a private class stays private
        ComponentArchitecture moved = moved(
                move("com.acme.repo.RepoService", "com.acme.repo.adapter", false));

        assertTrue(violations(moved).contains("BYPASS com.acme.web.Controller -> com.acme.repo.RepoService"));
    }

    @Test
    void aMovedPackageTakesItsClassesAndComponentsAlong() {
        // solr leaves repo: its own api is what the rest of the world may use now
        ComponentArchitecture moved = moved(move("com.acme.repo.adapter.solr", "com.acme.web", false));

        assertEquals(Set.of(), bypassesOf(moved, "com.acme.web.Controller", "com.acme.repo.adapter.solr.api.Solr"));
        assertEquals(Set.of("BYPASS com.acme.repo.adapter.solr.SolrClient -> com.acme.repo.RepoService"),
                bypassesOf(moved, "com.acme.repo.adapter.solr.SolrClient", "com.acme.repo.RepoService"));
    }

    @Test
    void aPlannedPackageIsAComponentOfItsOwn() {
        ComponentMoves hidden = new ComponentMoves(
                List.of(move("com.acme.repo.RepoService", "virtual-1", false)), Set.of("virtual-1"));
        assertEquals(Set.of("BYPASS com.acme.web.Controller -> com.acme.repo.RepoService"),
                bypassesOf(moved(hidden), "com.acme.web.Controller", "com.acme.repo.RepoService"));

        ComponentMoves published = new ComponentMoves(
                List.of(move("com.acme.repo.RepoService", "virtual-1", true)), Set.of("virtual-1"));
        assertEquals(Set.of(),
                bypassesOf(moved(published), "com.acme.web.Controller", "com.acme.repo.RepoService"));

        ComponentMoves deleted = new ComponentMoves(
                List.of(move("com.acme.repo.RepoService", "virtual-1", true)), Set.of());
        assertEquals(violations(build(nestedModel())), violations(moved(deleted)),
                "a move into a package that is gone is void");
    }

    @Test
    void aNameThatExistsAtTheDestinationDoesNotMixUpTheElements() {
        DomainModel domain = new DomainModel();
        pkgs(domain, "com", "com.acme", "com.acme.a", "com.acme.a.api", "com.acme.b", "com.acme.b.api", "com.acme.c");
        cls(domain, "com.acme.a.api.Service", false, 0, Set.of());
        cls(domain, "com.acme.b.api.Service", false, 0, Set.of());
        cls(domain, "com.acme.c.Client", false, 0, Set.of("com.acme.a.api.Service", "com.acme.b.api.Service"));

        // the api package of a becomes private in b, next to the api package b has
        ComponentArchitecture moved = new ComponentArchitectureBuilder().build(
                domain, ArchitectureAnnotations.empty(), null,
                new ComponentMoves(List.of(move("com.acme.a.api", "com.acme.b", false)), Set.of()));

        assertEquals(Set.of("BYPASS com.acme.c.Client -> com.acme.a.api.Service"), violations(moved));
    }

    private static ComponentMoves.Move move(String element, String container, boolean api) {
        return new ComponentMoves.Move(element, container, api);
    }

    private static ComponentArchitecture moved(ComponentMoves.Move... moves) {
        return moved(new ComponentMoves(List.of(moves), Set.of()));
    }

    private static ComponentArchitecture moved(ComponentMoves moves) {
        return new ComponentArchitectureBuilder().build(
                nestedModel(), ArchitectureAnnotations.empty(), null, moves);
    }

    private static Set<String> bypassesOf(ComponentArchitecture architecture, String source, String target) {
        return violations(architecture).stream()
                .filter(("BYPASS " + source + " -> " + target)::equals)
                .collect(Collectors.toSet());
    }

    @Test
    void componentProjectionDoesNotMutateCalculatedLevels() {
        DomainModel domain = nestedModel();
        Map<String, Integer> before = classLevels(domain);

        new ComponentArchitectureBuilder().build(domain, ArchitectureAnnotations.empty());

        assertEquals(before, classLevels(domain));
    }

    private static ComponentArchitecture build(DomainModel domain) {
        return new ComponentArchitectureBuilder().build(domain, ArchitectureAnnotations.empty());
    }

    private static ComponentArchitecture build(DomainModel domain, DependencyModel rawModel) {
        return new ComponentArchitectureBuilder().build(domain, ArchitectureAnnotations.empty(), rawModel);
    }

    private static List<String> roots(List<ComponentArchitecture.ComponentElement> components) {
        return components.stream().map(ComponentArchitecture.ComponentElement::rootPackageFqn).toList();
    }

    private static Set<String> violations(ComponentArchitecture architecture) {
        return architecture.violations().stream()
                .map(v -> (v.kind() == ViolationKind.COMPONENT_API_BYPASS ? "BYPASS " : "LEAKS ")
                        + v.sourceFqn() + " -> " + v.targetFqn())
                .collect(Collectors.toSet());
    }

    private static ComponentArchitecture.ComponentElement component(ComponentArchitecture architecture,
                                                                    String rootPackageFqn) {
        return architecture.allComponents().stream()
                .filter(c -> c.rootPackageFqn().equals(rootPackageFqn))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> apiFqns(ComponentArchitecture architecture, String rootPackageFqn) {
        return component(architecture, rootPackageFqn).api().stream()
                .map(Element::fqn)
                .collect(Collectors.toSet());
    }

    private static Set<String> implementationClasses(ComponentArchitecture architecture, String rootPackageFqn) {
        Set<String> classes = new HashSet<>();
        collectClasses(component(architecture, rootPackageFqn).implementationRows(), classes);
        return classes;
    }

    private static void collectClasses(List<List<Element>> rows, Set<String> classes) {
        for (List<Element> row : rows) {
            for (Element element : row) {
                if (element instanceof Element.PackageElement pkg) {
                    collectClasses(pkg.rows(), classes);
                } else {
                    classes.add(element.fqn());
                }
            }
        }
    }

    private static Map<String, Integer> classLevels(DomainModel domain) {
        Map<String, Integer> result = new LinkedHashMap<>();
        domain.getAllClasses().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().architectureLevel));
        return result;
    }

    /** repo contains solr, solr contains embedded. */
    private static DomainModel nestedModel() {
        DomainModel model = new DomainModel();
        pkgs(model, "com", "com.acme", "com.acme.repo", "com.acme.repo.api", "com.acme.repo.adapter",
                "com.acme.repo.adapter.solr", "com.acme.repo.adapter.solr.api",
                "com.acme.repo.adapter.solr.embedded", "com.acme.repo.adapter.solr.embedded.api", "com.acme.web");
        cls(model, "com.acme.repo.api.Repo", true, 0, Set.of("com.acme.repo.adapter.solr.api.Solr"));
        cls(model, "com.acme.repo.RepoService", false, 0, Set.of(
                "com.acme.repo.api.Repo",
                "com.acme.repo.adapter.solr.api.Solr",
                "com.acme.repo.adapter.solr.SolrClient",
                "com.acme.repo.adapter.solr.embedded.api.Embedded"));
        cls(model, "com.acme.repo.adapter.solr.api.Solr", true, 0, Set.of());
        // inner code sees the private part of the components around it
        cls(model, "com.acme.repo.adapter.solr.SolrClient", false, 0, Set.of(
                "com.acme.repo.RepoService",
                "com.acme.repo.adapter.solr.embedded.api.Embedded"));
        cls(model, "com.acme.repo.adapter.solr.embedded.api.Embedded", true, 0,
                Set.of("com.acme.repo.adapter.solr.SolrClient"));
        cls(model, "com.acme.web.Controller", false, 0, Set.of(
                "com.acme.repo.api.Repo",
                "com.acme.repo.RepoService",
                "com.acme.repo.adapter.solr.api.Solr"));
        return model;
    }

    private static DomainModel jpmsDomainModel() {
        DomainModel model = new DomainModel();

        pkg(model, "com", 0);
        pkg(model, "com.acme", 0);
        pkg(model, "com.acme.payment", 1);
        pkg(model, "com.acme.payment.contract", 1);
        pkg(model, "com.acme.payment.contract.internal", 0);
        pkg(model, "com.acme.payment.impl", 0);
        pkg(model, "com.acme.payment.reflect", 0);
        pkg(model, "com.acme.web", 2);

        cls(model, "com.acme.payment.PaymentApi", true, 2, Set.of());
        cls(model, "com.acme.payment.contract.PaymentFacade", false, 2, Set.of());
        cls(model, "com.acme.payment.contract.internal.InternalDto", false, 1, Set.of());
        cls(model, "com.acme.payment.reflect.ReflectionHook", false, 1, Set.of());
        cls(model, "com.acme.web.Controller", false, 3, Set.of());

        return model;
    }

    private static DependencyModel jpmsRawModel() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ModuleInfo module = new DependencyModel.ModuleInfo("com.acme.payment", null);
        module.addExportedPackage("com.acme.payment.contract", Set.of());
        module.addOpenedPackage("com.acme.payment.reflect", Set.of());
        model.addModule(module);
        return model;
    }

    private static void pkgs(DomainModel model, String... fqns) {
        for (String fqn : fqns) {
            pkg(model, fqn, 0);
        }
    }

    private static void pkg(DomainModel model, String fqn, int level) {
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        model.addPackage(fqn, new CalculatedElementInfo(fqn, simpleName, "PACKAGE", level, Set.of()));
    }

    private static void cls(DomainModel model,
                            String fqn,
                            boolean interfaceType,
                            int level,
                            Set<String> dependencies) {
        String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
        model.addClass(fqn, new CalculatedElementInfo(
                fqn, simpleName, "CLASS", level, dependencies, interfaceType));
    }
}
