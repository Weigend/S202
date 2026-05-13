package de.weigend.s202.analysis.strategy.impl;

import de.weigend.s202.analysis.strategy.ClassLevelCalculationStrategy;
import de.weigend.s202.analysis.strategy.ClassAggregationStrategy;
import de.weigend.s202.graph.TarjanSCCFinder;
import de.weigend.s202.graph.StronglyConnectedComponent;

import java.util.*;

/**
 * Basic class level calculation strategy.
 * Uses SCC analysis to handle cyclic dependencies correctly.
 * Classes in the same SCC (cycle) get the same level.
 */
public class BasicClassLevelCalculationStrategy implements ClassLevelCalculationStrategy {
    
    private final ClassAggregationStrategy aggregationStrategy;
    
    public BasicClassLevelCalculationStrategy(ClassAggregationStrategy aggregationStrategy) {
        this.aggregationStrategy = Objects.requireNonNull(aggregationStrategy, 
            "aggregationStrategy cannot be null");
    }
    
    @Override
    public Map<String, Integer> calculateClassLevels(Map<String, Set<String>> classDependencies) {
        Objects.requireNonNull(classDependencies, "classDependencies cannot be null");
        
        if (classDependencies.isEmpty()) {
            return new HashMap<>();
        }
        
        // Step 1: Find SCCs (strongly connected components) - classes in cycles
        TarjanSCCFinder sccFinder = new TarjanSCCFinder(classDependencies);
        List<StronglyConnectedComponent> sccs = sccFinder.findSCCs();
        
        // Step 2: Build a map from class to its SCC
        Map<String, StronglyConnectedComponent> classToScc = new HashMap<>();
        for (StronglyConnectedComponent scc : sccs) {
            for (String member : scc.getMembers()) {
                classToScc.put(member, scc);
            }
        }
        
        // Step 3: Build SCC dependency graph (DAG - no cycles between SCCs)
        Map<Integer, Set<Integer>> sccDependencies = new HashMap<>();
        Map<Integer, StronglyConnectedComponent> sccById = new HashMap<>();
        
        for (StronglyConnectedComponent scc : sccs) {
            sccById.put(scc.getId(), scc);
            Set<Integer> deps = new HashSet<>();
            
            for (String member : scc.getMembers()) {
                Set<String> memberDeps = classDependencies.getOrDefault(member, Set.of());
                for (String dep : memberDeps) {
                    StronglyConnectedComponent depScc = classToScc.get(dep);
                    if (depScc != null && depScc.getId() != scc.getId()) {
                        deps.add(depScc.getId());
                    }
                }
            }
            sccDependencies.put(scc.getId(), deps);
        }
        
        // Step 4: Calculate SCC levels (topological order)
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
        
        // Step 5: Assign class levels based on their SCC level
        Map<String, Integer> classLevels = new HashMap<>();
        for (String className : classDependencies.keySet()) {
            StronglyConnectedComponent scc = classToScc.get(className);
            if (scc != null) {
                classLevels.put(className, sccLevels.get(scc.getId()));
            } else {
                classLevels.put(className, 0);
            }
        }
        
        return classLevels;
    }
    
    @Override
    public String getName() {
        return "BasicClassLevelCalculation (SCC-aware, with " + aggregationStrategy.getName() + ")";
    }
}
