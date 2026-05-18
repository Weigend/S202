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

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.InputAnalyzer;
import de.weigend.s202.reader.DependencyModel;

public class TestLevelCalculatorDebug {
    public static void main(String[] args) throws Exception {
        String jarPath = "../test-example/target/test-example-1.0.0.jar";
        
        System.out.println("=== TESTING LEVEL CALCULATOR ===\n");
        
        // Step 1: Analyze
        InputAnalyzer analyzer = new InputAnalyzer();
        DependencyModel rawModel = analyzer.analyze(jarPath);
        System.out.println("Raw packages: " + rawModel.getAllPackageNames());
        
        // Step 2: Calculate
        LevelCalculator calc = new LevelCalculator();
        DomainModel domainModel = calc.calculate(rawModel);
        
        System.out.println("\n=== FINAL RESULT ===");
        System.out.println("DomainModel packages: " + domainModel.getAllPackages().size());
        for (DomainModel.CalculatedElementInfo pkg : domainModel.getAllPackages().values()) {
            System.out.println("  " + pkg.fullName + " -> L" + pkg.architectureLevel);
        }
    }
}
