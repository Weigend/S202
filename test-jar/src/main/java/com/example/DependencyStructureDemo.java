package com.example;

import java.util.*;

/**
 * Demo program showing the cyclic dependencies structure.
 * 
 * Dependency Graph:
 * ┌─────────────────────────────────────┐
 * │ TANGLE 1: a ↔ b                     │
 * │   a → b                             │
 * │   b → a (CYCLE!)                    │
 * └─────────────────────────────────────┘
 *   │
 *   └─→ VIOLATION: b → c (upward)
 *
 * ┌─────────────────────────────────────┐
 * │ TANGLE 2: c → d → e → c             │
 * │   c → d                             │
 * │   d → e                             │
 * │   e → c (CYCLE!)                    │
 * └─────────────────────────────────────┘
 * 
 * Expected Analysis Results:
 * - SCC #0: [a, b] (TANGLE) - Level 1
 * - SCC #1: [c, d, e] (TANGLE) - Level 0
 * - Violations: b → c (upward edge)
 * - Intra-SCC edges: 2 (a↔b) + 3 (c→d→e→c) = 5
 */
public class DependencyStructureDemo {
    public static void main(String[] args) {
        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║  SCC Algorithm Test - Cyclic Dependencies Analysis               ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝\n");
        
        System.out.println("📦 Package Dependency Structure:\n");
        
        System.out.println("┌─ TANGLE 1: Two-Node Cycle ─────────────────────────────────────┐");
        System.out.println("│                                                                  │");
        System.out.println("│  com.example.a                                                   │");
        System.out.println("│      ↓ (imports)                                                 │");
        System.out.println("│  com.example.b                                                   │");
        System.out.println("│      ↑ (imports back - CYCLE!)                                   │");
        System.out.println("│  com.example.a                                                   │");
        System.out.println("│                                                                  │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘\n");
        
        System.out.println("⚠️  VIOLATION: b → c (upward dependency)");
        System.out.println("    com.example.b imports com.example.c");
        System.out.println("    This should NOT happen (architectural violation)\n");
        
        System.out.println("┌─ TANGLE 2: Three-Node Cycle ──────────────────────────────────┐");
        System.out.println("│                                                                  │");
        System.out.println("│  com.example.c                                                   │");
        System.out.println("│      ↓ (imports)                                                 │");
        System.out.println("│  com.example.d                                                   │");
        System.out.println("│      ↓ (imports)                                                 │");
        System.out.println("│  com.example.e                                                   │");
        System.out.println("│      ↑ (imports back - CYCLE!)                                   │");
        System.out.println("│  com.example.c                                                   │");
        System.out.println("│                                                                  │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘\n");
        
        System.out.println("🔍 Expected SCC Analysis Results:\n");
        
        System.out.println("SCCs Found: 2");
        System.out.println("  • SCC #0: [a, b] (TANGLE - 2 members)");
        System.out.println("    - Level: 1");
        System.out.println("    - Dependencies: none (depends on SCC #1)");
        System.out.println("    - Internal edges: 2 (a→b, b→a)\n");
        
        System.out.println("  • SCC #1: [c, d, e] (TANGLE - 3 members)");
        System.out.println("    - Level: 0");
        System.out.println("    - Dependencies: none (leaf SCC)");
        System.out.println("    - Internal edges: 3 (c→d, d→e, e→c)\n");
        
        System.out.println("Edge Classification:");
        System.out.println("  • VIOLATIONS (upward): 1");
        System.out.println("    - b → c (architectural violation)\n");
        
        System.out.println("  • NORMAL (downward): 1");
        System.out.println("    - a → c (indirect, through b)\n");
        
        System.out.println("  • INTRA_SCC (internal): 5");
        System.out.println("    - a ↔ b (2 edges in SCC #0)");
        System.out.println("    - c → d → e → c (3 edges in SCC #1)\n");
        
        System.out.println("═══════════════════════════════════════════════════════════════════\n");
        
        printDependencyDeclaredInClasses();
    }
    
    private static void printDependencyDeclaredInClasses() {
        System.out.println("📝 Source Code Dependencies:\n");
        
        System.out.println("▶ File: src/main/java/com/example/a/A.java");
        System.out.println("  Imports: com.example.b.B");
        System.out.println("  Uses: B b = new B(); (CYCLE START)\n");
        
        System.out.println("▶ File: src/main/java/com/example/b/B.java");
        System.out.println("  Imports: com.example.a.A, com.example.c.C");
        System.out.println("  Uses: A a, C c = new C(); (CLOSES CYCLE a↔b, VIOLATION b→c)\n");
        
        System.out.println("▶ File: src/main/java/com/example/c/C.java");
        System.out.println("  Imports: com.example.d.D");
        System.out.println("  Uses: D d = new D(); (CYCLE START)\n");
        
        System.out.println("▶ File: src/main/java/com/example/d/D.java");
        System.out.println("  Imports: com.example.e.E");
        System.out.println("  Uses: E e = new E();\n");
        
        System.out.println("▶ File: src/main/java/com/example/e/E.java");
        System.out.println("  Imports: com.example.c.C");
        System.out.println("  Uses: C c = new C(); (CLOSES CYCLE c→d→e→c)\n");
        
        System.out.println("═══════════════════════════════════════════════════════════════════\n");
    }
}
