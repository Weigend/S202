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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Plain text with a title, a head of key/value lines and numbered sections. */
final class TextReport {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StringBuilder text = new StringBuilder();
    private int sections;

    TextReport(String title, String analysisName, List<String> sourcePaths) {
        line(title);
        line("=".repeat(title.length()));
        line("");
        head("Analysis", analysisName == null ? "" : analysisName);
        for (String path : sourcePaths) {
            head("Source", path);
        }
        head("Created", LocalDateTime.now().format(TIME) + " by S202");
    }

    void head(String key, String value) {
        line("%-10s %s".formatted(key + ":", value));
    }

    void section(String title) {
        sections++;
        String heading = sections + ". " + title;
        line("");
        line("");
        line(heading);
        line("-".repeat(heading.length()));
    }

    void line(String line) {
        text.append(line).append('\n');
    }

    /** Text that explains, wrapped for reading. */
    void paragraph(String paragraph) {
        StringBuilder current = new StringBuilder();
        for (String word : paragraph.split("\\s+")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > 90) {
                line(current.toString());
                current.setLength(0);
            }
            current.append(current.isEmpty() ? "" : " ").append(word);
        }
        line(current.toString());
    }

    static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
