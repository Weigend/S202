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
package de.weigend.s202.domain.strategy;

import de.weigend.s202.domain.architecture.LevelCalculationStrategyFactory;
import de.weigend.s202.domain.strategy.aggregation.SimpleMaxAggregationStrategy;
import de.weigend.s202.domain.strategy.impl.BasicClassLevelCalculationStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LevelCalculationStrategyContextTest {
    
    @Test
    void testCreateDefault() {
        LevelCalculationStrategyContext context = LevelCalculationStrategyFactory.createDefault();
        
        assertNotNull(context);
        assertNotNull(context.getClassLevelStrategy());
        assertNotNull(context.getAggregationStrategy());
    }
    
    @Test
    void testCreateCustom() {
        ClassAggregationStrategy aggregation = new SimpleMaxAggregationStrategy();
        ClassLevelCalculationStrategy classStrategy = 
            new BasicClassLevelCalculationStrategy(aggregation);
        
        LevelCalculationStrategyContext context = 
            new LevelCalculationStrategyContext(classStrategy, aggregation);
        
        assertNotNull(context);
        assertEquals(classStrategy, context.getClassLevelStrategy());
        assertEquals(aggregation, context.getAggregationStrategy());
    }
    
    @Test
    void testToString() {
        LevelCalculationStrategyContext context = LevelCalculationStrategyFactory.createDefault();
        String description = context.toString();
        
        assertNotNull(description);
        assertEquals(true, description.contains("LevelCalculationStrategyContext"));
        assertEquals(true, description.contains("HeuristicSCCBreaking"));
    }
    
    private void assertEquals(Object expected, Object actual) {
        assert expected.equals(actual) : "Expected " + expected + " but was " + actual;
    }
}
