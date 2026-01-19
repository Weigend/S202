package de.weigend.s202.test;

import de.weigend.s202.io.JarLoader;
import de.weigend.s202.analysis.DependencyGraphBuilder;
import de.weigend.s202.model.JavaClass;
import de.weigend.s202.model.JavaPackage;
import java.io.File;
import java.util.Map;

public class TestAnalyzer {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("/home/johannes/Programieren/Structure202/test-jar/target/test-cyclic-dependencies-1.0.0.jar");
        
        System.out.println("Loading JAR: " + jarFile);
        JarLoader loader = new JarLoader();
        Map<String, JavaClass> classes = loader.loadJar(jarFile);
        
        System.out.println("✓ Loaded " + classes.size() + " classes");
        
        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        for (JavaClass clazz : classes.values()) {
            builder.addClass(clazz);
        }
        
        System.out.println("✓ Building package hierarchy...");
        JavaPackage root = builder.buildPackageHierarchy("com");
        System.out.println("✓ Built package hierarchy with root: " + root.getPackageName());
        
        System.out.println("✓ Detecting cycles...");
        var cycles = builder.detectCycles(root);
        System.out.println("✓ Found " + cycles.size() + " cycles");
        for (var cycle : cycles) {
            System.out.println("  Cycle: " + cycle.getCycle());
        }
        
        System.out.println("✓ Building architecture model...");
        de.weigend.s202.analysis.ArchitectureModelBuilder modelBuilder = 
            new de.weigend.s202.analysis.ArchitectureModelBuilder();
        var model = modelBuilder.buildModel(root, 3);
        System.out.println("✓ Model built successfully");
        
        System.out.println("✓ Assigning layers...");
        de.weigend.s202.analysis.LayerAssigner layerAssigner = 
            new de.weigend.s202.analysis.LayerAssigner();
        layerAssigner.assignLayers(model);
        System.out.println("✓ Layers assigned successfully");
    }
}
