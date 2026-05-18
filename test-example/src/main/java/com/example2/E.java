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
package com.example2;

import com.example.B;
import com.example1.X;

/**
 * Cross-package dependency class.
 * E depends on:
 * - com.example2.A (same package)
 * - com.example.B (different package)
 * - com.example1.X (different package)
 * 
 * This demonstrates how packages should be leveled based on their internal dependencies.
 */
public class E {
    private A example2A = new A();
    private B exampleB = new B();
    private X example1X = new X();
    
    public void complexMethod() {
        example2A.methodA();
        exampleB.getAInfo();
        example1X.doSomething();
    }
}
