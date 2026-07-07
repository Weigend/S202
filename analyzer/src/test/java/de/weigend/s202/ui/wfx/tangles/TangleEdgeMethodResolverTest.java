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
package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.reader.DependencyModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TangleEdgeMethodResolverTest {

    @Test
    void edgeSelectionResolvesCalledTargetMethod() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ClassInfo caller = new DependencyModel.ClassInfo("pkg.A", "A", "pkg");
        DependencyModel.ClassInfo target = new DependencyModel.ClassInfo("pkg.B", "B", "pkg");

        caller.addMethod("source", "()V");
        target.addMethod("called", "(I)V");
        target.addMethod("called", "()V");

        DependencyModel.MethodInfo source = caller.getMethod("source", "()V");
        source.methodCalls.put("pkg.B.called", 1);
        source.methodCallDescriptors.computeIfAbsent("pkg.B.called", k -> new java.util.HashSet<>()).add("(I)V");

        model.addClass("pkg.A", caller);
        model.addClass("pkg.B", target);

        TangleEdgeMethodResolver.TargetMethod selected =
                TangleEdgeMethodResolver.firstTargetMethodCalledByDependency(model, "pkg.A", "pkg.B");

        assertEquals("pkg.B", selected.className());
        assertEquals("called", selected.methodName());
        assertEquals("(I)V", selected.descriptor());
    }

    @Test
    void edgeSelectionFallsBackToMethodNameWhenDescriptorIsUnknown() {
        DependencyModel model = new DependencyModel();
        DependencyModel.ClassInfo caller = new DependencyModel.ClassInfo("pkg.A", "A", "pkg");
        DependencyModel.ClassInfo target = new DependencyModel.ClassInfo("pkg.B", "B", "pkg");

        caller.addMethod("source", "()V");
        target.addMethod("called", "()V");
        caller.getMethod("source", "()V").methodCalls.put("pkg.B.called", 1);

        model.addClass("pkg.A", caller);
        model.addClass("pkg.B", target);

        TangleEdgeMethodResolver.TargetMethod selected =
                TangleEdgeMethodResolver.firstTargetMethodCalledByDependency(model, "pkg.A", "pkg.B");

        assertEquals("pkg.B", selected.className());
        assertEquals("called", selected.methodName());
        assertNull(selected.descriptor());
    }
}
