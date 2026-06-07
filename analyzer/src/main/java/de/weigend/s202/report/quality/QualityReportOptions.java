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

import java.util.Locale;

/**
 * Pure configuration value for a quality report run.
 */
public record QualityReportOptions(String scopeImageExtension, int scopeImageLimit) {

    public static final String DEFAULT_SCOPE_IMAGE_EXTENSION = "svg";
    public static final int DEFAULT_SCOPE_IMAGE_LIMIT = 5;
    public static final QualityReportOptions DEFAULT =
            new QualityReportOptions(DEFAULT_SCOPE_IMAGE_EXTENSION, DEFAULT_SCOPE_IMAGE_LIMIT);

    public QualityReportOptions {
        scopeImageExtension = normalizeExtension(scopeImageExtension);
        scopeImageLimit = Math.max(0, scopeImageLimit);
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return DEFAULT_SCOPE_IMAGE_EXTENSION;
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }
}
