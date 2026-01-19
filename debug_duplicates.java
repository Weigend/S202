import de.weigend.s202.analysis.input.InputAnalyzer;
import de.weigend.s202.analysis.input.DependencyModel;
import de.weigend.s202.analysis.domain.LevelCalculator;
import de.weigend.s202.analysis.domain.DomainModel;

import java.io.IOException;
import java.util.*;

public class debug_duplicates {
    public static void main(String[] args) throws IOException {
        InputAnalyzer analyzer = new InputAnalyzer();
        String jarPath = "/home/johannes/Programieren/Structure202/analyzer/target/s202-code-analyzer-1.0.0.jar";
        
        System.out.println("=== Analyzing JAR ===");
        DependencyModel rawModel = analyzer.analyze(jarPath);
        
        System.out.println("Total classes: " + rawModel.getClassCount());
        
        // Count occurrences of DependencyModel
        int count = 0;
        for (String className : rawModel.getAllClassNames()) {
            if (className.contains("DependencyModel")) {
                System.out.println("Found: " + className);
                count++;
            }
        }
        System.out.println("DependencyModel occurrences: " + count);
        
        System.out.println("\n=== All DependencyModel* classes ===");
        for (String className : rawModel.getAllClassNames()) {
            if (className.startsWith("de.weigend.s202.analysis.input.DependencyModel")) {
                System.out.println(className);
            }
        }
        
        System.out.println("\n=== After LevelCalculator ===");
        LevelCalculator levelCalc = new LevelCalculator();
        DomainModel domainModel = levelCalc.calculate(rawModel);
        
        count = 0;
        for (String className : domainModel.getAllClasses().keySet()) {
            if (className.contains("DependencyModel")) {
                System.out.println("Found: " + className);
                count++;
            }
        }
        System.out.println("DependencyModel occurrences: " + count);
    }
}
