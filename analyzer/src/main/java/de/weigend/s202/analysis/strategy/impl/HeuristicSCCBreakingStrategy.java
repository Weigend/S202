package de.weigend.s202.analysis.strategy.impl;

import de.weigend.s202.analysis.strategy.ClassLevelCalculationStrategy;
import de.weigend.s202.analysis.scc.SCCBreaker;
import de.weigend.s202.analysis.scc.TarjanSCCFinder;
import de.weigend.s202.analysis.scc.StronglyConnectedComponent;

import java.util.*;

/**
 * Advanced class level calculation strategy that breaks large SCCs using heuristics.
 * 
 * <h2>Motivation</h2>
 * The basic strategy puts all classes in an SCC on the same level. For projects with
 * many cycles (like Minecraft), this results in most classes being on the same level,
 * making the visualization useless.
 * 
 * <h2>Solution</h2>
 * This strategy uses {@link SCCBreaker} to identify "back edges" that should be ignored
 * for level calculation. This creates a more meaningful hierarchy even for cyclic code.
 * 
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Use SCCBreaker to identify back edges (cycle-causing edges to ignore)</li>
 *   <li>Calculate levels on the modified graph (without back edges)</li>
 *   <li>Back edges are tracked as "violations" but don't affect level calculation</li>
 * </ol>
 * 
 * @see SCCBreaker for the heuristic used to identify back edges
 */
public class HeuristicSCCBreakingStrategy implements ClassLevelCalculationStrategy {
    
    /** Back edges identified during the last calculation (for reporting) */
    private Set<SCCBreaker.Edge> lastIdentifiedBackEdges = new HashSet<>();
    
    @Override
    public Map<String, Integer> calculateClassLevels(Map<String, Set<String>> classDependencies) {
        Objects.requireNonNull(classDependencies, "classDependencies cannot be null");
        
        if (classDependencies.isEmpty()) {
            return new HashMap<>();
        }
        
        // Step 1: Use SCCBreaker to identify back edges
        SCCBreaker breaker = new SCCBreaker(classDependencies);
        lastIdentifiedBackEdges = breaker.findBackEdges();
        
        // Step 2: Get the modified graph without back edges
        Map<String, Set<String>> modifiedGraph = breaker.getGraphWithoutBackEdges();
        
        // Step 3: Calculate levels on the modified graph
        // The modified graph should have much smaller (or no) SCCs
        return calculateLevelsOnDAG(modifiedGraph, classDependencies.keySet());
    }
    
    /**
     * Calculates levels on a graph that is either a DAG or has only small SCCs.
     * Uses iterative approach similar to BasicClassLevelCalculationStrategy.
     */
    private Map<String, Integer> calculateLevelsOnDAG(Map<String, Set<String>> graph, Set<String> allClasses) {
        // Find any remaining SCCs
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(graph);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        
        // Build a map from class to its SCC
        Map<String, StronglyConnectedComponent> classToScc = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                classToScc.put(member, scc);
            }
        }
        
        // Build SCC dependency graph
        Map<Integer, Set<Integer>> sccDependencies = new HashMap<>();
        Map<Integer, StronglyConnectedComponent> sccById = new HashMap<>();
        
        for (StronglyConnectedComponent scc : sccs) {
            sccById.put(scc.getId(), scc);
            Set<Integer> deps = new HashSet<>();
            
            for (String member : scc.getMembers()) {
                Set<String> memberDeps = graph.getOrDefault(member, Set.of());
                for (String dep : memberDeps) {
                    StronglyConnectedComponent depScc = classToScc.get(dep);
                    if (depScc != null && depScc.getId() != scc.getId()) {
                        deps.add(depScc.getId());
                    }
                }
            }
            sccDependencies.put(scc.getId(), deps);
        }
        
        // Calculate SCC levels
        Map<Integer, Integer> sccLevels = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            sccLevels.put(scc.getId(), 0);
        }
        
        // Iteratively calculate SCC levels until stable
        boolean changed = true;
        int iterations = 0;
        int maxIterations = sccs.size() + 10;
        
        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;
            
            for (StronglyConnectedComponent scc : sccs) {
                Set<Integer> deps = sccDependencies.get(scc.getId());
                int maxDepLevel = -1;
                
                for (Integer depId : deps) {
                    int depLevel = sccLevels.get(depId);
                    if (depLevel > maxDepLevel) {
                        maxDepLevel = depLevel;
                    }
                }
                
                int newLevel = maxDepLevel >= 0 ? maxDepLevel + 1 : 0;
                if (sccLevels.get(scc.getId()) != newLevel) {
                    sccLevels.put(scc.getId(), newLevel);
                    changed = true;
                }
            }
        }
        
        // Assign class levels based on their SCC level
        Map<String, Integer> classLevels = new HashMap<>();
        for (String className : allClasses) {
            StronglyConnectedComponent scc = classToScc.get(className);
            if (scc != null) {
                classLevels.put(className, sccLevels.get(scc.getId()));
            } else {
                classLevels.put(className, 0);
            }
        }
        
        return classLevels;
    }
    
    /**
     * Returns the back edges identified during the last calculation.
     * These represent "violations" - dependencies that go against the natural hierarchy.
     * 
     * @return Set of back edges
     */
    public Set<SCCBreaker.Edge> getLastIdentifiedBackEdges() {
        return new HashSet<>(lastIdentifiedBackEdges);
    }
    
    /**
     * Returns the number of back edges identified during the last calculation.
     */
    public int getBackEdgeCount() {
        return lastIdentifiedBackEdges.size();
    }
    
    @Override
    public String getName() {
        return "HeuristicSCCBreaking (breaks large cycles using in/out-degree heuristics)";
    }
}
