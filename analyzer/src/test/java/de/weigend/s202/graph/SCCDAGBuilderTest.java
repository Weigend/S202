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
package de.weigend.s202.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Tests for SCC DAG builder and level assignment.
 */
public class SCCDAGBuilderTest {
    
    private List<StronglyConnectedComponent> sccs;
    private Map<String, Set<String>> graph;
    
    @BeforeEach
    public void setUp() {
        sccs = new ArrayList<>();
        graph = new HashMap<>();
    }
    
    @Test
    public void testSimpleDAGLevelAssignment() {
        // Create SCCs: SCC0 (A), SCC1 (B), SCC2 (C)
        // Dependencies: A -> B -> C (linear)
        sccs.add(new StronglyConnectedComponent(0, new HashSet<>(Arrays.asList("A"))));
        sccs.add(new StronglyConnectedComponent(1, new HashSet<>(Arrays.asList("B"))));
        sccs.add(new StronglyConnectedComponent(2, new HashSet<>(Arrays.asList("C"))));
        
        graph.put("A", new HashSet<>(Arrays.asList("B")));
        graph.put("B", new HashSet<>(Arrays.asList("C")));
        graph.put("C", new HashSet<>());
        
        SCCDAGBuilder builder = new SCCDAGBuilder(sccs, graph);
        builder.buildDAG();
        builder.assignLevels();
        
        assertEquals(0, sccs.get(2).getLevel(), "Leaf SCC should have level 0");
        assertEquals(1, sccs.get(1).getLevel(), "Middle SCC should have level 1");
        assertEquals(2, sccs.get(0).getLevel(), "Top SCC should have level 2");
    }
    
    @Test
    public void testDiamondDAG() {
        // Diamond structure: A -> {B, C} -> D
        sccs.add(new StronglyConnectedComponent(0, new HashSet<>(Arrays.asList("A"))));
        sccs.add(new StronglyConnectedComponent(1, new HashSet<>(Arrays.asList("B"))));
        sccs.add(new StronglyConnectedComponent(2, new HashSet<>(Arrays.asList("C"))));
        sccs.add(new StronglyConnectedComponent(3, new HashSet<>(Arrays.asList("D"))));
        
        graph.put("A", new HashSet<>(Arrays.asList("B", "C")));
        graph.put("B", new HashSet<>(Arrays.asList("D")));
        graph.put("C", new HashSet<>(Arrays.asList("D")));
        graph.put("D", new HashSet<>());
        
        SCCDAGBuilder builder = new SCCDAGBuilder(sccs, graph);
        builder.buildDAG();
        builder.assignLevels();
        
        assertEquals(0, sccs.get(3).getLevel(), "Leaf should have level 0");
        assertEquals(1, sccs.get(1).getLevel());
        assertEquals(1, sccs.get(2).getLevel());
        assertEquals(2, sccs.get(0).getLevel(), "Root should have level 2");
    }
    
    @Test
    public void testSortedSCCs() {
        // Create unsorted SCCs with different levels
        StronglyConnectedComponent scc0 = new StronglyConnectedComponent(0, new HashSet<>(Arrays.asList("A")));
        StronglyConnectedComponent scc1 = new StronglyConnectedComponent(1, new HashSet<>(Arrays.asList("B")));
        StronglyConnectedComponent scc2 = new StronglyConnectedComponent(2, new HashSet<>(Arrays.asList("C")));
        
        scc0.setLevel(2);
        scc1.setLevel(0);
        scc2.setLevel(1);
        
        sccs.add(scc0);
        sccs.add(scc1);
        sccs.add(scc2);
        
        graph.put("A", new HashSet<>());
        graph.put("B", new HashSet<>());
        graph.put("C", new HashSet<>());
        
        SCCDAGBuilder builder = new SCCDAGBuilder(sccs, graph);
        List<StronglyConnectedComponent> sorted = builder.getSortedSCCs();
        
        assertEquals(0, sorted.get(0).getLevel());
        assertEquals(1, sorted.get(1).getLevel());
        assertEquals(2, sorted.get(2).getLevel());
    }
}
