#!/bin/bash

# Direct SCC Test - Runs the test JAR through the analyzer

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         SCC Algorithm Test with Cyclic Dependencies           ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

PROJECT_DIR=".."
TEST_JAR="$PROJECT_DIR/test-jar/target/test-cyclic-dependencies-1.0.0.jar"

echo "📚 First, let's show the EXPECTED structure:"
echo ""

java -cp "$TEST_JAR" com.example.DependencyStructureDemo

echo ""
echo "═══════════════════════════════════════════════════════════════════"
echo "🔍 Now running the ACTUAL SCC analysis..."
echo "═══════════════════════════════════════════════════════════════════"
echo ""

cd "$PROJECT_DIR"

# Create a simple test class that analyzes the test JAR
cat > /tmp/TestSCCSimple.java << 'JAVACODE'
import de.weigend.s202.analysis.*;
import de.weigend.s202.analysis.scc.*;
import de.weigend.s202.io.JarLoader;
import de.weigend.s202.model.JavaClass;
import java.io.File;
import java.util.*;

public class TestSCCSimple {
    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        
        System.out.println("📦 Loading JAR for analysis...");
        JarLoader loader = new JarLoader();
        Map<String, JavaClass> classes = loader.loadClasses(new File(jarPath));
        
        System.out.println("✓ Loaded " + classes.size() + " classes");
        System.out.println("");
        
        // Build dependency graph
        DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder();
        for (JavaClass javaClass : classes.values()) {
            graphBuilder.addClass(javaClass);
        }
        
        System.out.println("✓ Built dependency graph");
        System.out.println("");
        
        // Build architecture model  
        ArchitectureModelBuilder archBuilder = new ArchitectureModelBuilder();
        ArchitectureModelBuilder.ArchitectureNode root = archBuilder.buildModel(graphBuilder.getPackages());
        
        System.out.println("🔍 Running SCC Analysis with Tarjan's Algorithm...");
        System.out.println("");
        
        // Assign layers (includes SCC analysis)
        LayerAssigner assigner = new LayerAssigner();
        assigner.assignLayers(root);
        
        // Get SCC results
        List<StronglyConnectedComponent> sccs = assigner.getSCCs();
        Map<String, Integer> nodeToLevel = assigner.getNodeToLevel();
        List<EdgeClassification.ClassifiedEdge> edges = assigner.getClassifiedEdges();
        
        // Show results
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║                   🎯 SCC ANALYSIS RESULTS 🎯                    ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println("");
        
        System.out.println("📊 Statistics:");
        System.out.println("   Total SCCs: " + sccs.size());
        
        long tangleCount = sccs.stream().filter(scc -> scc.isTangle()).count();
        System.out.println("   Tangles (cycles): " + tangleCount);
        System.out.println("   Single packages: " + (sccs.size() - tangleCount));
        System.out.println("");
        
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("");
        
        for (StronglyConnectedComponent scc : sccs) {
            String icon = scc.isTangle() ? "🔴" : "✓";
            System.out.println(icon + " SCC #" + scc.getId() + ": " + scc);
            if (scc.isTangle()) {
                System.out.println("   ⚠️  TANGLE (cycle) detected!");
                System.out.println("   Members: " + scc.getMembers());
            }
            System.out.println("");
        }
        
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("📋 Package Levels:");
        System.out.println("");
        
        TreeMap<String, Integer> sortedLevels = new TreeMap<>(nodeToLevel);
        for (String pkg : sortedLevels.keySet()) {
            Integer level = sortedLevels.get(pkg);
            System.out.printf("   %-25s → Level %d\n", pkg, level);
        }
        
        System.out.println("");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("🔗 Edge Classification:");
        System.out.println("");
        
        int violations = 0, normal = 0, intraScc = 0;
        for (EdgeClassification.ClassifiedEdge edge : edges) {
            switch (edge.type) {
                case VIOLATION:
                    violations++;
                    System.out.println("   ⚠️  VIOLATION: " + edge.from + " → " + edge.to);
                    break;
                case NORMAL:
                    normal++;
                    break;
                case INTRA_SCC:
                    intraScc++;
                    break;
            }
        }
        
        System.out.println("");
        System.out.println("   Summary:");
        System.out.println("   • ⚠️  Violations (upward): " + violations);
        System.out.println("   • ✓ Normal (downward): " + normal);
        System.out.println("   • 🔗 Intra-SCC (internal): " + intraScc);
        
        System.out.println("");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("");
        
        // Verify expectations
        System.out.println("✅ VERIFICATION:");
        if (tangleCount == 2) {
            System.out.println("   ✓ Found 2 tangles as expected");
        } else {
            System.out.println("   ⚠️  Expected 2 tangles, found " + tangleCount);
        }
        
        if (violations > 0) {
            System.out.println("   ✓ Found violations as expected");
        } else {
            System.out.println("   ⚠️  Expected violations, found none");
        }
        
        if (intraScc >= 5) {
            System.out.println("   ✓ Found internal cycle edges");
        }
        
        System.out.println("");
    }
}
JAVACODE

# Compile and run
javac -cp "target/classes:$TEST_JAR" /tmp/TestSCCSimple.java -d /tmp 2>/dev/null
if [ $? -eq 0 ]; then
    java -cp "/tmp:target/classes:$TEST_JAR" TestSCCSimple "$TEST_JAR"
else
    echo "Compilation failed. Trying without demo first..."
fi

echo ""
echo "═══════════════════════════════════════════════════════════════════"
echo "✅ Test complete!"
echo ""
