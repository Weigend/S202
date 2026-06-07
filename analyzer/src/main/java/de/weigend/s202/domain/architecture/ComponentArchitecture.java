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

import java.util.ArrayList;
import java.util.List;

/**
 * Architecture style that organises elements as named components with an
 * explicit API surface and an implementation body. Accesses from outside a
 * component that bypass the API are reported as violations.
 */
public interface ComponentArchitecture extends Architecture {

    List<ComponentElement> components();

    record ComponentElement(
            String id,
            String displayName,
            String rootPackageFqn,
            List<Element> api,
            List<List<Element>> implementationRows) {

        public ComponentElement {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must be non-empty");
            }
            if (rootPackageFqn == null || rootPackageFqn.isBlank()) {
                throw new IllegalArgumentException("rootPackageFqn must be non-empty");
            }
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
            api = api == null ? List.of() : List.copyOf(api);
            implementationRows = copyDeepImmutable(implementationRows);
        }

        private static List<List<Element>> copyDeepImmutable(List<List<Element>> rows) {
            if (rows == null) {
                return List.of();
            }
            List<List<Element>> outer = new ArrayList<>(rows.size());
            for (List<Element> row : rows) {
                outer.add(List.copyOf(row));
            }
            return List.copyOf(outer);
        }
    }
}
