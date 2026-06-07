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
 * Facade for building and writing a complete quality report.
 */
public final class QualityReportExporter {

    private final QualityReportModelBuilder modelBuilder;
    private final QualityReportHtmlWriter htmlWriter;

    public QualityReportExporter() {
        this(new QualityReportModelBuilder(), new QualityReportHtmlWriter());
    }

    public QualityReportExporter(String scopeImageExtension) {
        this(new QualityReportModelBuilder(scopeImageExtension), new QualityReportHtmlWriter());
    }

    public QualityReportExporter(String scopeImageExtension, int scopeImageLimit) {
        this(new QualityReportModelBuilder(scopeImageExtension, scopeImageLimit), new QualityReportHtmlWriter());
    }

    QualityReportExporter(QualityReportModelBuilder modelBuilder,
                          QualityReportHtmlWriter htmlWriter) {
        this.modelBuilder = modelBuilder;
        this.htmlWriter = htmlWriter;
    }

    public Path export(QualityReportInput input, Path outputDirectory) throws IOException {
        QualityReportModel model = modelBuilder.build(input);
        return htmlWriter.write(model, outputDirectory);
    }

    public Path export(QualityReportInput input,
                       Path outputDirectory,
                       QualityReportImageRenderer imageRenderer) throws IOException {
        QualityReportModel model = modelBuilder.build(input);
        if (imageRenderer != null) {
            imageRenderer.renderImages(model, input, outputDirectory);
        }
        return htmlWriter.write(model, outputDirectory);
    }
}
