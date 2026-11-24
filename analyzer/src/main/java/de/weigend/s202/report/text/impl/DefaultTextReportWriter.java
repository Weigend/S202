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
package de.weigend.s202.report.text.impl;

import de.weigend.s202.domain.architecture.ArchitectureInsightsProvider;
import de.weigend.s202.report.text.RefactoringReportInput;
import de.weigend.s202.report.text.TextReportWriter;
import de.weigend.s202.report.text.ViolationReportInput;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Objects;

/** Lookup-provided writer of the text reports. */
@Singleton
public final class DefaultTextReportWriter implements TextReportWriter {

    private final ArchitectureInsightsProvider insights;

    @Inject
    public DefaultTextReportWriter(ArchitectureInsightsProvider insights) {
        this.insights = Objects.requireNonNull(insights, "insights");
    }

    @Override
    public String refactoringReport(RefactoringReportInput input) {
        return new RefactoringReportText(insights, input).write();
    }

    @Override
    public String violationReport(ViolationReportInput input) {
        return new ViolationReportText(insights, input).write();
    }
}
