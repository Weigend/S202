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

import java.util.*;

/**
 * Represents a strongly connected component (SCC) - a maximal set of nodes where
 * every node is reachable from every other node.
 * 
 * SCCs with size > 1 represent cycles in the dependency graph (tangles).
 */
public class StronglyConnectedComponent implements Comparable<StronglyConnectedComponent> {
    private final int id;
    private final Set<String> members;
    private final Set<String> outgoingDependencies;
    private final Set<String> incomingDependencies;
    private int level;
    
    public StronglyConnectedComponent(int id, Set<String> members) {
        this.id = id;
        this.members = new HashSet<>(members);
        this.outgoingDependencies = new HashSet<>();
        this.incomingDependencies = new HashSet<>();
        this.level = -1;
    }
    
    public int getId() {
        return id;
    }
    
    public Set<String> getMembers() {
        return new HashSet<>(members);
    }
    
    public boolean isTangle() {
        return members.size() > 1;
    }
    
    public int getSize() {
        return members.size();
    }
    
    public void addOutgoingDependency(String depSccId) {
        outgoingDependencies.add(depSccId);
    }
    
    public void addIncomingDependency(String depSccId) {
        incomingDependencies.add(depSccId);
    }
    
    public Set<String> getOutgoingDependencies() {
        return new HashSet<>(outgoingDependencies);
    }
    
    public Set<String> getIncomingDependencies() {
        return new HashSet<>(incomingDependencies);
    }
    
    public int getLevel() {
        return level;
    }
    
    public void setLevel(int level) {
        this.level = level;
    }
    
    @Override
    public int compareTo(StronglyConnectedComponent other) {
        // Sort by level ascending, then by ID for stability
        if (this.level != other.level) {
            return Integer.compare(this.level, other.level);
        }
        return Integer.compare(this.id, other.id);
    }
    
    @Override
    public String toString() {
        return String.format("SCC[id=%d, size=%d, level=%d, members=%s]", 
            id, members.size(), level, members);
    }
}
