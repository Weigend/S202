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
package de.weigend.s202.ui.wfx.shell;

import java.io.File;

/**
 * Remembers the directories the user last picked in the various choosers so
 * every dialog reopens where the user left off. One instance is shared by the
 * open-, project- and report-controllers; the fallback chains mirror the
 * historical S202Module behaviour.
 */
public final class RecentDirectories {

    private File lastDirectory;            // last JAR pick
    private File lastProjectDirectory;     // last Maven/Gradle/source root
    private File lastProjectFileDirectory; // last .s202.json location
    private File lastReportDirectory;      // last quality-report export target

    public File lastDirectory() {
        return lastDirectory;
    }

    public void setLastDirectory(File dir) {
        this.lastDirectory = dir;
    }

    public void setLastProjectDirectory(File dir) {
        this.lastProjectDirectory = dir;
    }

    public void setLastProjectFileDirectory(File dir) {
        this.lastProjectFileDirectory = dir;
    }

    public void setLastReportDirectory(File dir) {
        this.lastReportDirectory = dir;
    }

    /** JAR chooser: plain last directory. */
    public File initialJarDirectory() {
        return existing(lastDirectory);
    }

    /** Source-root chooser: project root first, then JAR directory. */
    public File initialSourceDirectory() {
        File project = existing(lastProjectDirectory);
        return project != null ? project : existing(lastDirectory);
    }

    /** Unified open chooser: JAR directory first, then project root. */
    public File initialAnyDirectory() {
        File jar = existing(lastDirectory);
        return jar != null ? jar : existing(lastProjectDirectory);
    }

    /** Project-file chooser (.s202.json): project file first, then JAR directory. */
    public File initialProjectFileDirectory() {
        File projectFile = existing(lastProjectFileDirectory);
        return projectFile != null ? projectFile : existing(lastDirectory);
    }

    /** Report export: report → project file → project root → JAR directory. */
    public File initialReportDirectory() {
        File report = existing(lastReportDirectory);
        if (report != null) {
            return report;
        }
        File projectFile = existing(lastProjectFileDirectory);
        if (projectFile != null) {
            return projectFile;
        }
        return initialSourceDirectory();
    }

    private static File existing(File dir) {
        return dir != null && dir.isDirectory() ? dir : null;
    }
}
