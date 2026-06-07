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
package de.weigend.s202.report.quality;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Optional hook for renderers that create report evidence images from a richer
 * runtime context, for example JavaFX snapshots of actual ArchitectureViews.
 */
@FunctionalInterface
public interface QualityReportImageRenderer {

    void renderImages(QualityReportModel model,
                      QualityReportInput input,
                      Path outputDirectory) throws IOException;
}
