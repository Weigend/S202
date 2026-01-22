package de.weigend.s202.debug;

import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.DomainModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.ui.model.ArchitectureNode;
import de.weigend.s202.ui.model.ArchitectureNodeBuilder;
import de.weigend.s202.ui.ArchitectureView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Test ArchitectureView with real data to see if packages are displayed with correct levels.
 */
public class DebugArchitectureViewTest extends Application {
    
    @Override
    public void start(Stage primaryStage) throws Exception {
        System.err.println("[TEST] Starting ArchitectureView test...\n");
        
        // Load test JAR
        String testJarPath = "/home/johannes/Programieren/Structure202/test-example/target/test-example-1.0.0.jar";
        
        System.err.println("[TEST] Step 1: InputAnalyzer.analyze()");
        InputAnalyzer inputAnalyzer = new InputAnalyzer();
        DependencyModel rawModel = inputAnalyzer.analyze(testJarPath);
        System.err.println("[TEST]   Raw packages: " + rawModel.getAllPackageNames().size());
        
        System.err.println("\n[TEST] Step 2: LevelCalculator.calculate()");
        LevelCalculator levelCalculator = new LevelCalculator();
        DomainModel calculatedModel = levelCalculator.calculate(rawModel);
        System.err.println("[TEST]   Calculated packages: " + calculatedModel.getAllPackages().size());
        
        System.err.println("\n[TEST] Step 3: ArchitectureNodeBuilder.build()");
        ArchitectureNodeBuilder builder = new ArchitectureNodeBuilder();
        ArchitectureNode rootNode = builder.build(calculatedModel);
        System.err.println("[TEST]   ArchitectureNode total nodes: " + rootNode.getTotalNodeCount());
        
        System.err.println("\n[TEST] Step 4: Create ArchitectureView and set ArchitectureNode");
        ArchitectureView architectureView = new ArchitectureView(primaryStage);
        System.err.println("[TEST]   Calling setArchitectureRoot()...");
        architectureView.setArchitectureRoot(rootNode);
        System.err.println("[TEST]   setArchitectureRoot() completed");
        
        // Display the view
        Scene scene = new Scene(architectureView, 1000, 600);
        primaryStage.setTitle("Architecture View Test - Packages should show correct levels");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.err.println("\n[TEST] UI is now showing. Check package box labels:");
        System.err.println("[TEST]   - com should show (L:0)");
        System.err.println("[TEST]   - com.example should show (L:0)");
        System.err.println("[TEST]   - com.example1 should show (L:0)");
        System.err.println("[TEST]   - com.example2 should show (L:1) ← THIS IS THE KEY TEST!");
        System.err.println("[TEST] Close the window when done.\n");
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
