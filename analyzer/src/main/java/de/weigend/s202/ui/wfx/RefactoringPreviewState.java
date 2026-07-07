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
package de.weigend.s202.ui.wfx;

import de.weigend.s202.domain.DependencyEdge;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Die sitzungsweiten What-If-Preview-Cuts (im Tangle-View „geschnittene“
 * Kanten). Geteilt zwischen Tangle-Steuerung (schreibt), View-Erzeugung und
 * Quality-Report (lesen beide) — vorher ein implizit geteiltes Feld in
 * S202Module.
 */
public final class RefactoringPreviewState {

    private final Set<DependencyEdge> cuts = new HashSet<>();

    public Set<DependencyEdge> cuts() {
        return Collections.unmodifiableSet(cuts);
    }

    public void add(DependencyEdge edge) {
        cuts.add(edge);
    }

    public void addAll(Set<DependencyEdge> edges) {
        cuts.addAll(edges);
    }

    public void remove(DependencyEdge edge) {
        cuts.remove(edge);
    }

    public void clear() {
        cuts.clear();
    }
}
