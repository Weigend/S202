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

/** Writes the reports of an analysis as plain text. */
public interface TextReportWriter {

    /** What the user changed compared to the analyzed code: moves, component mapping, cuts. */
    String refactoringReport(RefactoringReportInput input);

    /** The architecture violations of the analyzed code, with what it takes to fix them. */
    String violationReport(ViolationReportInput input);
}
