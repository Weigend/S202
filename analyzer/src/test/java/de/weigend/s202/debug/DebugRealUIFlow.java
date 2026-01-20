package de.weigend.s202.debug;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.ui.model.UIModel;
import de.weigend.s202.ui.model.UIModelBuilder;

/**
 * Simulates exactly what AnalyzerApplication does when you load a JAR.
 */
public class DebugRealUIFlow {
    public static void main(String[] args) throws Exception {
        System.err.println("[DEBUG] Starting real UI flow simulation...\n");
        
        // Load test JAR
        String testJarPath = "/home/johannes/Programieren/Structure202/test-example/target/test-example-1.0.0.jar";
        
        System.err.println("[DEBUG] Step 1: InputAnalyzer.analyze()");
        InputAnalyzer inputAnalyzer = new InputAnalyzer();
        DependencyModel rawModel = inputAnalyzer.analyze(testJarPath);
        System.err.println("[DEBUG]   Raw packages: " + rawModel.getAllPackageNames().size());
        System.err.println("[DEBUG]   Raw classes: " + rawModel.getAllClasses().size());
        
        System.err.println("\n[DEBUG] Step 2: LevelCalculator.calculate()");
        LevelCalculator levelCalculator = new LevelCalculator();
        DomainModel calculatedModel = levelCalculator.calculate(rawModel);
        System.err.println("[DEBUG]   Calculated packages: " + calculatedModel.getAllPackages().size());
        System.err.println("[DEBUG]   Packages with levels:");
        for (DomainModel.CalculatedElementInfo pkg : calculatedModel.getAllPackages().values()) {
            System.err.println("[DEBUG]     - " + pkg.fullName + " -> L" + pkg.level);
        }
        
        System.err.println("\n[DEBUG] Step 3: UIModelBuilder.build()");
        UIModelBuilder uiModelBuilder = new UIModelBuilder();
        UIModel uiModel = uiModelBuilder.build(calculatedModel);
        System.err.println("[DEBUG]   UIModel total elements: " + uiModel.getTotalElementCount());
        System.err.println("[DEBUG]   UIModel level count: " + uiModel.getLevelCount());
        
        System.err.println("\n[DEBUG] Step 4: Check what ArchitectureView would receive");
        System.err.println("[DEBUG]   UIModel packages:");
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo elem : uiModel.getElementsAtLevel(level)) {
                if ("PACKAGE".equals(elem.type)) {
                    System.err.println("[DEBUG]     - " + elem.fullName + " (type=" + elem.type + ", level=" + elem.level + ")");
                }
            }
        }
        
        System.err.println("\n[DEBUG] FINAL RESULT:");
        System.err.println("[DEBUG]   Expected com.example2 at level 1");
        boolean found = false;
        for (int level = 0; level < uiModel.getLevelCount(); level++) {
            for (UIModel.UIElementInfo elem : uiModel.getElementsAtLevel(level)) {
                if ("com.example2".equals(elem.fullName)) {
                    System.err.println("[DEBUG]   FOUND: " + elem.fullName + " at level " + elem.level);
                    found = true;
                }
            }
        }
        if (!found) {
            System.err.println("[DEBUG]   ERROR: com.example2 not found in UIModel!");
        }
    }
}
