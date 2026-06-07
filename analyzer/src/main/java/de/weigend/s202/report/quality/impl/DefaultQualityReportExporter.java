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
package de.weigend.s202.report.quality.impl;

import de.weigend.s202.domain.architecture.ArchitectureInsightsProvider;
import de.weigend.s202.report.quality.QualityReportExporter;
import de.weigend.s202.report.quality.QualityReportImageRenderer;
import de.weigend.s202.report.quality.QualityReportInput;
import de.weigend.s202.report.quality.QualityReportModel;
import de.weigend.s202.report.quality.QualityReportOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Lookup-provided quality report implementation.
 */
@Singleton
public final class DefaultQualityReportExporter implements QualityReportExporter {

    private final ArchitectureInsightsProvider architectureInsights;
    private final QualityReportHtmlWriter htmlWriter;

    @Inject
    public DefaultQualityReportExporter(ArchitectureInsightsProvider architectureInsights) {
        this.architectureInsights = Objects.requireNonNull(architectureInsights, "architectureInsights");
        this.htmlWriter = new QualityReportHtmlWriter();
    }

    @Override
    public QualityReportModel build(QualityReportInput input) {
        return build(input, QualityReportOptions.DEFAULT);
    }

    @Override
    public QualityReportModel build(QualityReportInput input, QualityReportOptions options) {
        QualityReportOptions effectiveOptions = options == null ? QualityReportOptions.DEFAULT : options;
        return new QualityReportModelBuilder(
                architectureInsights,
                effectiveOptions.scopeImageExtension(),
                effectiveOptions.scopeImageLimit())
                .build(input);
    }

    @Override
    public Path write(QualityReportModel model, Path outputDirectory) throws IOException {
        return htmlWriter.write(model, outputDirectory);
    }

    @Override
    public Path export(QualityReportInput input, Path outputDirectory) throws IOException {
        return export(input, outputDirectory, null, QualityReportOptions.DEFAULT);
    }

    @Override
    public Path export(QualityReportInput input,
                       Path outputDirectory,
                       QualityReportImageRenderer imageRenderer) throws IOException {
        return export(input, outputDirectory, imageRenderer, QualityReportOptions.DEFAULT);
    }

    @Override
    public Path export(QualityReportInput input,
                       Path outputDirectory,
                       QualityReportImageRenderer imageRenderer,
                       QualityReportOptions options) throws IOException {
        QualityReportModel model = build(input, options);
        if (imageRenderer != null) {
            imageRenderer.renderImages(model, input, outputDirectory);
        }
        return write(model, outputDirectory);
    }
}
