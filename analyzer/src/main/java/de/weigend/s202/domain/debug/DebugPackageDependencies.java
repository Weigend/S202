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
package de.weigend.s202.domain.debug;

import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;

import java.util.*;

public class DebugPackageDependencies {
    public static void main(String[] args) throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        
        System.out.println("Analyzing: " + jarPath);
        
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel model = analyzer.analyze(jarPath);
        
        System.out.println("\n=== CLASS DEPENDENCIES ===");
        for (String className : new TreeSet<>(model.getAllClassNames())) {
            if (className.startsWith("com.example")) {
                DependencyModel.ClassInfo info = model.getClass(className);
                System.out.println(className);
                for (String dep : new TreeSet<>(info.dependencies)) {
                    System.out.println("  -> " + dep);
                }
            }
        }
        
        System.out.println("\n=== PACKAGE DEPENDENCIES ===");
        Set<String> packages = new TreeSet<>();
        for (String className : model.getAllClassNames()) {
            if (className.startsWith("com.example")) {
                DependencyModel.ClassInfo info = model.getClass(className);
                packages.add(info.packageName);
            }
        }
        
        for (String pkgName : packages) {
            DependencyModel.PackageInfo pkgInfo = model.getPackage(pkgName);
            System.out.println(pkgName + ":");
            
            Set<String> externalDeps = new HashSet<>();
            for (String className : pkgInfo.classNames) {
                DependencyModel.ClassInfo classInfo = model.getClass(className);
                for (String depClassName : classInfo.dependencies) {
                    DependencyModel.ClassInfo depClassInfo = model.getClass(depClassName);
                    if (depClassInfo != null && !depClassInfo.packageName.equals(pkgName)) {
                        externalDeps.add(depClassInfo.packageName);
                    }
                }
            }
            
            if (externalDeps.isEmpty()) {
                System.out.println("  (no external dependencies)");
            } else {
                for (String dep : new TreeSet<>(externalDeps)) {
                    System.out.println("  -> " + dep);
                }
            }
        }
    }
}
