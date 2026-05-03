package de.weigend.s202.ui.wfx;

import de.weigend.s202.reader.DependencyModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class S202ModuleTest {

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

        S202Module.TargetMethod selected =
                S202Module.firstTargetMethodCalledByDependency(model, "pkg.A", "pkg.B");

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

        S202Module.TargetMethod selected =
                S202Module.firstTargetMethodCalledByDependency(model, "pkg.A", "pkg.B");

        assertEquals("pkg.B", selected.className());
        assertEquals("called", selected.methodName());
        assertNull(selected.descriptor());
    }
}
