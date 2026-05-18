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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads and writes Structure202 project files.
 */
public final class S202ProjectStore {

    private final ObjectMapper mapper;

    public S202ProjectStore() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(Path path, S202Project project) throws IOException {
        mapper.writeValue(path.toFile(), project);
    }

    public S202Project load(Path path) throws IOException {
        S202Project project = mapper.readValue(path.toFile(), S202Project.class);
        validate(project);
        return project;
    }

    private static void validate(S202Project project) throws IOException {
        if (project == null) {
            throw new IOException("Project file is empty");
        }
        if (!S202Project.FORMAT.equals(project.format())) {
            throw new IOException("Unsupported project format: " + project.format());
        }
        if (project.formatVersion() != S202Project.FORMAT_VERSION) {
            throw new IOException("Unsupported project format version: " + project.formatVersion());
        }
        if (project.dependencyModel() == null || project.domainModel() == null) {
            throw new IOException("Project file does not contain a complete analysis model");
        }
    }
}
