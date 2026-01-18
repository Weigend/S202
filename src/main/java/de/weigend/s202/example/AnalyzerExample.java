package de.weigend.s202.example;

import de.weigend.s202.analysis.ArchitectureModelBuilder;
import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.DependencyGraphBuilder;
import de.weigend.s202.model.ClassDependency;
import de.weigend.s202.model.JavaClass;
import de.weigend.s202.model.JavaPackage;

/**
 * Example demonstrating how to use the S202 Code Analyzer.
 */
public class AnalyzerExample {

    public static void main(String[] args) {
        // Create example classes
        JavaClass userService = createClass("com.example.service.UserService");
        JavaClass authService = createClass("com.example.service.AuthService");
        JavaClass userRepository = createClass("com.example.repository.UserRepository");
        JavaClass authRepository = createClass("com.example.repository.AuthRepository");

        // Add dependencies
        addDependency(userService, authService, "uses for authentication");
        addDependency(userService, userRepository, "uses for data access");
        addDependency(authService, authRepository, "uses for credential storage");
        addDependency(authRepository, userRepository, "uses for user lookup");

        // Build dependency graph
        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.addClass(userService);
        builder.addClass(authService);
        builder.addClass(userRepository);
        builder.addClass(authRepository);

        // Build package hierarchy
        JavaPackage rootPackage = builder.buildPackageHierarchy("com");

        // Detect cycles
        var cycles = builder.detectCycles(rootPackage);
        System.out.println("Detected cycles: " + cycles.size());
        for (var cycle : cycles) {
            System.out.println("  - " + cycle);
        }

        // Build architecture model for UI
        ArchitectureModelBuilder uiBuilder = new ArchitectureModelBuilder();
        ArchitectureNode model = uiBuilder.buildModel(rootPackage, 3);

        // Print hierarchy
        printHierarchy(model, 0);
    }

    private static JavaClass createClass(String className) {
        return new JavaClass(className);
    }

    private static void addDependency(JavaClass source, JavaClass target, String description) {
        ClassDependency dep = new ClassDependency(
            source.getClassName(),
            target.getClassName(),
            ClassDependency.DependencyType.DIRECT
        );
        source.addDependency(dep);
    }

    private static void printHierarchy(ArchitectureNode node, int depth) {
        String indent = "  ".repeat(depth);
        String icon = node.getType().toString().equals("PACKAGE") ? "📦" : "📄";
        System.out.println(indent + icon + " " + node.getSimpleName() + 
            (node.getDependencies().isEmpty() ? "" : " (" + node.getDependencies().size() + " deps)"));

        for (ArchitectureNode child : node.getChildren()) {
            printHierarchy(child, depth + 1);
        }
    }
}
