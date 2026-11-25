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
package de.weigend.s202.ui.shell;

import io.softwareecg.wfx.main.Main;
import javafx.application.Application;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the S202 Code Analyzer running on the WFX rich-client platform
 * using the lookup strategy provided by the WFX launcher. The arguments are the
 * files to open: JARs, or one {@code pom.xml} or Gradle script.
 */
public class S202Main extends Main {

    private static List<File> filesToOpen = List.of();

    /** The files named on the command line. */
    static List<File> filesToOpen() {
        return filesToOpen;
    }

    public static void main(String[] args) {
        filesToOpen = Arrays.stream(args)
                // The javafx-maven-plugin (run-ui.sh) leaves the quotes of a path with blanks in place.
                .map(arg -> arg.replaceAll("^\"(.*)\"$", "$1"))
                .map(File::new)
                .map(File::getAbsoluteFile)
                .toList();
        Application.launch(S202Main.class, args);
    }
}
