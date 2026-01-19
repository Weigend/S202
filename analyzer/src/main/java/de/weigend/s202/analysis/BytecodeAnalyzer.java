package de.weigend.s202.analysis;

import de.weigend.s202.model.ClassDependency;
import de.weigend.s202.model.JavaClass;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Analyzes bytecode of a Java class to extract dependencies.
 * Uses ASM library for bytecode parsing.
 */
public class BytecodeAnalyzer {

    /**
     * Analyzes a class from its bytecode stream.
     *
     * @param className Full qualified class name
     * @param bytecodeStream Input stream containing the .class file
     * @return JavaClass with extracted dependencies
     * @throws IOException if reading the bytecode fails
     */
    public JavaClass analyzeClass(String className, InputStream bytecodeStream) throws IOException {
        JavaClass javaClass = new JavaClass(className);
        
        ClassReader reader = new ClassReader(bytecodeStream);
        DependencyExtractor extractor = new DependencyExtractor(className);
        reader.accept(extractor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        
        javaClass.addDependencies(extractor.getDependencies());
        return javaClass;
    }

    /**
     * ASM ClassVisitor that extracts dependencies from bytecode.
     */
    private static class DependencyExtractor extends ClassVisitor {
        private final String sourceClass;
        private final Set<ClassDependency> dependencies;
        private final Set<String> visitedClasses; // Avoid duplicates

        DependencyExtractor(String sourceClass) {
            super(Opcodes.ASM9);
            this.sourceClass = sourceClass;
            this.dependencies = new HashSet<>();
            this.visitedClasses = new HashSet<>();
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                         String superName, String[] interfaces) {
            // Super class dependency
            if (superName != null && !superName.equals("java/lang/Object")) {
                addDependency(toClassName(superName), ClassDependency.DependencyType.INHERITANCE);
            }

            // Interface dependencies
            if (interfaces != null) {
                for (String iface : interfaces) {
                    addDependency(toClassName(iface), ClassDependency.DependencyType.INHERITANCE);
                }
            }

            // Signature dependencies (generic types)
            if (signature != null) {
                extractSignatureDependencies(signature);
            }

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            // Field type dependencies
            extractTypeDescriptorDependencies(descriptor);
            if (signature != null) {
                extractSignatureDependencies(signature);
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            // Method parameter and return type dependencies
            extractTypeDescriptorDependencies(descriptor);
            if (signature != null) {
                extractSignatureDependencies(signature);
            }

            // Exception dependencies
            if (exceptions != null) {
                for (String exception : exceptions) {
                    addDependency(toClassName(exception), ClassDependency.DependencyType.DIRECT);
                }
            }

            return new MethodDependencyVisitor(this);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            extractTypeDescriptorDependencies(descriptor);
            return new AnnotationDependencyVisitor(this);
        }

        private void extractTypeDescriptorDependencies(String descriptor) {
            // Parse type descriptor like "Ljava/lang/String;[Ljava/util/List;"
            for (int i = 0; i < descriptor.length(); i++) {
                if (descriptor.charAt(i) == 'L') {
                    int end = descriptor.indexOf(';', i);
                    if (end > i) {
                        String classRef = descriptor.substring(i + 1, end);
                        addDependency(toClassName(classRef), ClassDependency.DependencyType.DIRECT);
                        i = end;
                    }
                }
            }
        }

        private void extractSignatureDependencies(String signature) {
            // Parse signature like "Ljava/util/List<Ljava/lang/String;>;"
            for (int i = 0; i < signature.length(); i++) {
                if (signature.charAt(i) == 'L') {
                    int end = signature.indexOf(';', i);
                    if (end > i) {
                        String classRef = signature.substring(i + 1, end);
                        addDependency(toClassName(classRef), ClassDependency.DependencyType.GENERIC);
                        i = end;
                    }
                }
            }
        }

        private void addDependency(String targetClass, ClassDependency.DependencyType type) {
            // Filter out Java standard library and avoid self-references
            if (!shouldIgnore(targetClass) && !targetClass.equals(sourceClass)) {
                dependencies.add(new ClassDependency(sourceClass, targetClass, type));
                visitedClasses.add(targetClass);
            }
        }

        private boolean shouldIgnore(String className) {
            // Skip Java standard library classes
            return className.startsWith("java.") || 
                   className.startsWith("javax.") ||
                   className.startsWith("sun.") ||
                   className.startsWith("com.sun.");
        }

        private String toClassName(String internalName) {
            return internalName.replace('/', '.');
        }

        Set<ClassDependency> getDependencies() {
            return new HashSet<>(dependencies);
        }
    }

    /**
     * Visitor for method instructions to extract dependencies.
     */
    private static class MethodDependencyVisitor extends MethodVisitor {
        private final DependencyExtractor extractor;

        MethodDependencyVisitor(DependencyExtractor extractor) {
            super(Opcodes.ASM9);
            this.extractor = extractor;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            extractor.addDependency(extractor.toClassName(type), ClassDependency.DependencyType.DIRECT);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            extractor.addDependency(extractor.toClassName(owner), ClassDependency.DependencyType.DIRECT);
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            extractor.addDependency(extractor.toClassName(owner), ClassDependency.DependencyType.DIRECT);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            extractor.extractTypeDescriptorDependencies(descriptor);
            return new AnnotationDependencyVisitor(extractor);
        }
    }

    /**
     * Visitor for annotations.
     */
    private static class AnnotationDependencyVisitor extends AnnotationVisitor {
        private final DependencyExtractor extractor;

        AnnotationDependencyVisitor(DependencyExtractor extractor) {
            super(Opcodes.ASM9);
            this.extractor = extractor;
        }

        @Override
        public void visit(String name, Object value) {
            // Annotation values can reference types
            super.visit(name, value);
        }
    }
}
