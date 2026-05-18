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
package de.weigend.s202.domain.strategy.aggregation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleMaxAggregationStrategyTest {
    
    private final SimpleMaxAggregationStrategy strategy = new SimpleMaxAggregationStrategy();
    
    @Test
    void testAggregateEmptySet() {
        Set<Integer> levels = new HashSet<>();
        assertEquals(0, strategy.aggregate(levels));
    }
    
    @Test
    void testAggregateSingleLevel() {
        Set<Integer> levels = Set.of(2);
        assertEquals(3, strategy.aggregate(levels));
    }
    
    @Test
    void testAggregateMultipleLevels() {
        Set<Integer> levels = Set.of(1, 2, 0);
        assertEquals(3, strategy.aggregate(levels)); // max(1,2,0) + 1 = 3
    }
    
    @Test
    void testAggregateZeroLevel() {
        Set<Integer> levels = Set.of(0);
        assertEquals(1, strategy.aggregate(levels));
    }
    
    @Test
    void testAggregateLargeLevels() {
        Set<Integer> levels = Set.of(5, 10, 3);
        assertEquals(11, strategy.aggregate(levels)); // max(5,10,3) + 1 = 11
    }
    
    @Test
    void testGetName() {
        assertEquals("SimpleMaxAggregation", strategy.getName());
    }
}
