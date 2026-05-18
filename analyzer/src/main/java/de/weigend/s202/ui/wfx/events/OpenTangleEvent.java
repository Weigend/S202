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
package de.weigend.s202.ui.wfx.events;

import java.util.EventObject;
import java.util.Set;

/**
 * Bus event published by the Top Tangles side panel when the user requests
 * a dedicated view focused on a single tangle. Carries the tangle's class
 * full-names plus a stable key/title so the host shell can keep one view
 * per tangle entry.
 */
public class OpenTangleEvent extends EventObject {

    private final Set<String> members;
    private final String tangleKey;
    private final String title;

    public OpenTangleEvent(Set<String> members, String tangleKey, String title, Object source) {
        super(source);
        this.members = Set.copyOf(members);
        this.tangleKey = tangleKey;
        this.title = title;
    }

    /** Class full-names that form the tangle. */
    public Set<String> getMembers() {
        return members;
    }

    /** Stable identity for this tangle entry. */
    public String getTangleKey() {
        return tangleKey;
    }

    /** User-facing title for the dedicated tangle view. */
    public String getTitle() {
        return title;
    }
}
