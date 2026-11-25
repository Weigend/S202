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
package de.weigend.s202.ui.core.canvas;

import de.weigend.s202.domain.architecture.WhatIfSource;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.Architecture;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureContext;
import de.weigend.s202.domain.architecture.ArchitectureKind;
import de.weigend.s202.domain.architecture.ArchitectureStyle;
import de.weigend.s202.domain.architecture.ComponentArchitecture;
import de.weigend.s202.domain.architecture.ComponentMoves;
import de.weigend.s202.domain.architecture.WhatIfArchitecture;
import de.weigend.s202.reader.DependencyModel;
import io.softwareecg.wfx.lookup.api.Lookup;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;
import java.util.function.Supplier;

/**
 * Hält die Analyse-Modelle einer Architektur-View (DomainModel, rohes
 * Dependency-Modell, Annotations) und leitet daraus die Architektur-Projektion
 * ({@link Architecture} + What-If-Kopie) für den aktiven View-Stil ab.
 * UI-frei; aus ArchitectureCanvas extrahiert. Die Property-Instanzen leben
 * hier, die View reicht sie unverändert nach außen — externe Bindings
 * behalten ihre Identität.
 */
final class ArchitectureProjectionModel {

    private final ReadOnlyObjectWrapper<DomainModel> domainModel = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<DependencyModel> rawDependencyModel = new ReadOnlyObjectWrapper<>(null);
    private final SimpleObjectProperty<ArchitectureAnnotations> architectureAnnotations =
            new SimpleObjectProperty<>(ArchitectureAnnotations.empty());
    private final ReadOnlyObjectWrapper<Architecture> architecture = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyObjectWrapper<WhatIfArchitecture> whatIfArchitecture = new ReadOnlyObjectWrapper<>(null);

    private final Supplier<ArchitectureKind> viewStyle;
    /** Die Verschiebungen der Component-Ansicht; sie ändern deren Verstöße. */
    private final Supplier<ComponentMoves> componentMoves;
    /** Läuft nach jedem Projektion-Rebuild (SCC-Renderer-Tangles nachziehen). */
    private final Runnable afterRebuild;

    ArchitectureProjectionModel(Supplier<ArchitectureKind> viewStyle,
                                Supplier<ComponentMoves> componentMoves,
                                Runnable afterRebuild) {
        this.viewStyle = viewStyle;
        this.componentMoves = componentMoves;
        this.afterRebuild = afterRebuild;
    }

    ArchitectureKind viewStyle() {
        return viewStyle.get();
    }

    /* ----- Properties (Instanzen bleiben stabil) ---------------------------- */

    ReadOnlyObjectProperty<DomainModel> domainModelProperty() {
        return domainModel.getReadOnlyProperty();
    }

    DomainModel getDomainModel() {
        return domainModel.get();
    }

    void setDomainModel(DomainModel model) {
        domainModel.set(model);
        rebuildArchitectureProjection();
    }

    ReadOnlyObjectProperty<DependencyModel> rawDependencyModelProperty() {
        return rawDependencyModel.getReadOnlyProperty();
    }

    DependencyModel getRawDependencyModel() {
        return rawDependencyModel.get();
    }

    /** Setzt nur die Property; Stil-abhängige Rebuild-Entscheidung trifft der Aufrufer. */
    void setRawDependencyModelValue(DependencyModel model) {
        rawDependencyModel.set(model);
    }

    SimpleObjectProperty<ArchitectureAnnotations> architectureAnnotationsProperty() {
        return architectureAnnotations;
    }

    ArchitectureAnnotations getArchitectureAnnotations() {
        ArchitectureAnnotations annotations = architectureAnnotations.get();
        return annotations == null ? ArchitectureAnnotations.empty() : annotations;
    }

    void setArchitectureAnnotations(ArchitectureAnnotations annotations) {
        architectureAnnotations.set(annotations == null ? ArchitectureAnnotations.empty() : annotations);
        rebuildArchitectureProjection();
    }

    ReadOnlyObjectProperty<Architecture> architectureProperty() {
        return architecture.getReadOnlyProperty();
    }

    Architecture getArchitecture() {
        return architecture.get();
    }

    ReadOnlyObjectProperty<WhatIfArchitecture> whatIfArchitectureProperty() {
        return whatIfArchitecture.getReadOnlyProperty();
    }

    WhatIfArchitecture getWhatIfArchitecture() {
        return whatIfArchitecture.get();
    }

    /* ----- Projektion -------------------------------------------------------- */

    void rebuildArchitectureProjection() {
        DomainModel model = domainModel.get();
        if (model == null) {
            architecture.set(null);
            whatIfArchitecture.set(null);
            return;
        }
        Architecture original;
        ArchitectureKind style = viewStyle.get();
        ArchitectureContext context = new ArchitectureContext(
                rawDependencyModel.get(),
                model,
                getArchitectureAnnotations(),
                style == ArchitectureKind.COMPONENT ? componentMoves.get() : ComponentMoves.none());
        if (style == ArchitectureKind.COMPONENT) {
            original = requireArchitectureStyle(ArchitectureKind.COMPONENT).build(context);
        } else if (style == ArchitectureKind.HEXAGONAL) {
            original = requireArchitectureStyle(ArchitectureKind.HEXAGONAL).build(context);
        } else {
            original = requireArchitectureStyle(ArchitectureKind.LAYERED).build(context);
        }
        architecture.set(original);
        whatIfArchitecture.set(original instanceof WhatIfSource source
                ? source.toWhatIf(model)
                : null);
        afterRebuild.run();
    }

    /** Wurzel der innersten Komponente, die {@code fqn} ist oder enthält; {@code null}, wenn keine. */
    String componentOf(String fqn) {
        if (!(architecture.get() instanceof ComponentArchitecture components) || fqn == null) {
            return null;
        }
        String innermost = null;
        for (ComponentArchitecture.ComponentElement component : components.allComponents()) {
            String root = component.rootPackageFqn();
            if ((fqn.equals(root) || fqn.startsWith(root + "."))
                    && (innermost == null || root.length() > innermost.length())) {
                innermost = root;
            }
        }
        return innermost;
    }

    /** Die für Paket-Zyklen maßgebliche Architektur: What-If, sonst Original. */
    Architecture packageCycleArchitecture() {
        WhatIfArchitecture wif = whatIfArchitecture.get();
        return wif != null ? wif : architecture.get();
    }

    private static ArchitectureStyle requireArchitectureStyle(ArchitectureKind kind) {
        List<ArchitectureStyle> styles = Lookup.findAll(ArchitectureStyle.class);
        return styles.stream()
                .filter(style -> style.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No architecture style registered for " + kind));
    }
}
