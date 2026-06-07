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
package de.weigend.s202.project;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Persists and restores {@link S202Project} snapshots to/from disk.
 */
public interface ProjectStore {

    void save(Path path, S202Project project) throws IOException;

    S202Project load(Path path) throws IOException;
}
