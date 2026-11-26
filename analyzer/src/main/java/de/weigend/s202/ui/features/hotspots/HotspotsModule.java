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
package de.weigend.s202.ui.features.hotspots;

import de.weigend.s202.analysis.quality.Hotspots;
import de.weigend.s202.analysis.quality.QualityCalculator;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.Externals;
import de.weigend.s202.domain.Origins;
import de.weigend.s202.domain.Placement;
import de.weigend.s202.domain.SCCFinder;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import de.weigend.s202.ui.core.events.FromToEvent;
import de.weigend.s202.ui.core.events.OpenScopeEvent;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.PlanState;
import de.weigend.s202.ui.features.whatif.WhatIfDependenciesView;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.lookup.api.LookupContext;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

import java.util.ArrayList;
import java.util.EventObject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WFX module providing the Hotspots panel. Follows
 * {@link WindowManager#getActiveLookup()} like the Quality panel: it ranks
 * the packages of the active architecture tab, as that shows them - with the
 * plan or without.
 */
// Priority 31: after From / To (30), with which this one shares its place.
@Singleton
@Priority(31)
public class HotspotsModule implements Module {

    private HotspotsView view;
    private LookupContext.Subscription activeViewSubscription;

    private ArchitectureCanvas boundView;
    private ChangeListener<ArchitectureNode> rootListener;
    private ChangeListener<DomainModel> modelListener;
    private ChangeListener<PlanState> planListener;

    @Override
    public String getName() {
        return "Hotspots";
    }

    @Override
    public void preload() {
        view = new HotspotsView();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        WindowManager wm = Lookup.lookup(WindowManager.class);
        View dependencies = wm.findView(WhatIfDependenciesView.VIEW_ID);
        if (dependencies != null) {
            wm.register(view, dependencies);
        } else {
            wm.register(view);
        }
        view.setActions(new HotspotsView.PackageActions() {
            @Override
            public void select(String pkg) {
                if (boundView != null) {
                    boundView.selectByFullName(pkg);
                    if (boundView.hasMatrix()) {
                        boundView.getMatrix().setSelectedElement(pkg);
                        boundView.getMatrix().reveal(pkg);
                    }
                }
            }

            @Override
            public void openScope(String pkg) {
                if (boundView != null) {
                    bus.publish(new OpenScopeEvent(pkg, boundView, view));
                }
            }

            @Override
            public void fromTo(String from, String to) {
                if (boundView != null) {
                    bus.publish(new FromToEvent(from, to, boundView));
                }
            }
        });

        LookupContext active = wm.getActiveLookup();
        activeViewSubscription = active.observe(ArchitectureCanvas.class,
                canvases -> rebindTo(canvases.isEmpty() ? null : canvases.get(0)));
        rebindTo(active.lookup(ArchitectureCanvas.class));
    }

    @Override
    public void stop() {
        if (activeViewSubscription != null) {
            activeViewSubscription.cancel();
            activeViewSubscription = null;
        }
        unbind();
    }

    private void rebindTo(ArchitectureCanvas newBound) {
        if (newBound == boundView) {
            return;
        }
        unbind();
        boundView = newBound;
        if (newBound != null) {
            rootListener = (obs, was, isNow) -> refresh();
            modelListener = (obs, was, isNow) -> refresh();
            planListener = (obs, was, isNow) -> refresh();
            newBound.architectureRootProperty().addListener(rootListener);
            newBound.domainModelProperty().addListener(modelListener);
            newBound.planProperty().addListener(planListener);
        }
        refresh();
    }

    private void unbind() {
        if (boundView != null) {
            boundView.architectureRootProperty().removeListener(rootListener);
            boundView.domainModelProperty().removeListener(modelListener);
            boundView.planProperty().removeListener(planListener);
        }
        boundView = null;
        rootListener = null;
        modelListener = null;
        planListener = null;
    }

    private void refresh() {
        ArchitectureNode root = boundView == null ? null : boundView.getArchitectureRoot();
        DomainModel model = boundView == null ? null : boundView.getShownModel();
        DependencyModel raw = boundView == null ? null : boundView.getRawDependencyModel();
        if (root == null || model == null || raw == null) {
            view.show(List.of(), List.of(), List.of());
            return;
        }
        Placement placement = boundView.getPlan().placement();
        Set<String> shown = new HashSet<>();
        collectClasses(root, shown);
        Externals externals = Externals.of(raw);
        QualityCalculator calculator = new QualityCalculator(model, Lookup.lookup(SCCFinder.class), placement);
        Map<String, String> planned = boundView.getPlannedPackages();
        view.setNames(pkg -> planned.getOrDefault(pkg, pkg));
        view.show(Hotspots.of(model, calculator, placement, externals, shown), libraries(externals, shown),
                Origins.of(raw));
    }

    private static List<HotspotsView.Library> libraries(Externals externals, Set<String> shown) {
        List<HotspotsView.Library> result = new ArrayList<>();
        externals.packages().forEach((pkg, classes) -> {
            Set<String> users = new HashSet<>(externals.usersOfPackage(pkg));
            users.retainAll(shown);
            if (!users.isEmpty()) {
                Set<String> usedClasses = new HashSet<>();
                Set<String> packages = new HashSet<>();
                for (String user : users) {
                    externals.usedBy(user).keySet().stream()
                            .filter(cls -> Externals.packageOf(cls).equals(pkg))
                            .forEach(usedClasses::add);
                    packages.add(Externals.packageOf(user));
                }
                result.add(new HotspotsView.Library(pkg, usedClasses.size(), users.size(), packages.size()));
            }
        });
        return result;
    }

    private static void collectClasses(ArchitectureNode node, Set<String> classes) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            classes.add(node.getFullName());
        }
        node.getChildren().forEach(child -> collectClasses(child, classes));
    }
}
