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
package de.weigend.s202.report.text;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.reader.DependencyModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @param analysisName display name of the analysis
 * @param sourcePaths  what was analyzed
 * @param annotations  the component mapping as the user left it
 * @param cuts         the dependencies cut in the refactoring preview
 * @param views        the changes made in the views of the analysis
 */
public record RefactoringReportInput(
        String analysisName,
        List<String> sourcePaths,
        DependencyModel rawModel,
        DomainModel domainModel,
        ArchitectureAnnotations annotations,
        Set<DependencyEdge> cuts,
        List<ViewChanges> views) {

    public RefactoringReportInput {
        Objects.requireNonNull(rawModel, "rawModel cannot be null");
        Objects.requireNonNull(domainModel, "domainModel cannot be null");
        sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        annotations = annotations == null ? ArchitectureAnnotations.empty() : annotations;
        cuts = cuts == null ? Set.of() : Set.copyOf(cuts);
        views = views == null ? List.of() : List.copyOf(views);
    }

    /**
     * @param view            title of the view
     * @param componentView   whether it shows components; its moves change their violations
     * @param plannedPackages packages the user added, id to name
     * @param moves           in the order they were made
     */
    public record ViewChanges(String view,
                              boolean componentView,
                              Map<String, String> plannedPackages,
                              List<ElementMove> moves) {
        public ViewChanges {
            plannedPackages = plannedPackages == null ? Map.of() : Map.copyOf(plannedPackages);
            moves = moves == null ? List.of() : List.copyOf(moves);
        }
    }

    /**
     * @param containerFqn package the element was dropped into: a package of the code, the id of a
     *                     planned one, or empty for the top level
     * @param row          row inside the package, counted from the top
     * @param api          dropped into the API area of a component
     */
    public record ElementMove(String elementFqn, String containerFqn, int row, boolean api) {
        public ElementMove {
            Objects.requireNonNull(elementFqn, "elementFqn cannot be null");
            containerFqn = containerFqn == null ? "" : containerFqn;
        }
    }
}
