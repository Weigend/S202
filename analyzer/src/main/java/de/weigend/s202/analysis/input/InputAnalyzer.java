package de.weigend.s202.analysis.input;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Analyzes Java bytecode from JAR files and extracts dependency information.
 * This is the raw analysis layer - NO layer calculation, NO UI dependencies.
 */
public class InputAnalyzer {

    /**
     * Analyzes a JAR file and returns raw dependency model.
     */
    public DependencyModel analyze(String jarPath) throws IOException {
        DependencyModel model = new DependencyModel();

        // Load classes from JAR
        try (JarFile jarFile = new JarFile(jarPath)) {
            jarFile.entries().asIterator().forEachRemaining(entry -> {
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    try {
                        byte[] classBytes = jarFile.getInputStream(entry).readAllBytes();
                        analyzeClass(entry.getName(), classBytes, model);
                    } catch (Exception e) {
                        System.err.println("Warning: Could not analyze " + entry.getName() + ": " + e.getMessage());
                    }
                }
            });
        }

        // Build package hierarchy
        buildPackageHierarchy(model);

        return model;
    }

    /**
     * Analyzes a single class file and extracts its information.
     */
    private void analyzeClass(String classPath, byte[] bytecode, DependencyModel model) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            DependencyExtractor extractor = new DependencyExtractor(model);
            reader.accept(extractor, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            System.err.println("Error analyzing class: " + e.getMessage());
        }
    }

    /**
     * Builds the package hierarchy from all loaded classes.
     */
    private void buildPackageHierarchy(DependencyModel model) {
        Map<String, DependencyModel.PackageInfo> packages = new HashMap<>();

        for (String className : model.getAllClassNames()) {
            DependencyModel.ClassInfo classInfo = model.getClass(className);
            String packageName = classInfo.packageName;

            // Create all parent packages
            String[] parts = packageName.split("\\.");
            String current = "";
            for (String part : parts) {
                String parentPkg = current.isEmpty() ? current : current + ".";
                current = parentPkg + part;

                if (!packages.containsKey(current)) {
                    DependencyModel.PackageInfo pkgInfo = new DependencyModel.PackageInfo(
                        current, part
                    );
                    packages.put(current, pkgInfo);

                    // Add to parent if exists
                    if (!parentPkg.isEmpty() && parentPkg.endsWith(".")) {
                        String parent = parentPkg.substring(0, parentPkg.length() - 1);
                        if (packages.containsKey(parent)) {
                            packages.get(parent).childPackages.add(current);
                        }
                    }
                }
            }

            // Add class to its package
            if (packages.containsKey(packageName)) {
                packages.get(packageName).classNames.add(className);
            }
        }

        // Store packages in model
        model.setPackages(packages);
    }

    /**
     * ASM ClassVisitor to extract dependencies from bytecode.
     */
    private static class DependencyExtractor extends ClassVisitor {
        private final DependencyModel model;
        private String currentClassName;
        private String currentPackageName;
        private String currentSimpleName;
        private DependencyModel.ClassInfo currentClassInfo;

        public DependencyExtractor(DependencyModel model) {
            super(Opcodes.ASM9);
            this.model = model;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            // Convert bytecode name (com/example/Class) to source name (com.example.Class)
            this.currentClassName = convertClassName(name);
            
            // Skip inner classes (contain $)
            // OPEN POINT: Inner classes are currently ignored. This means that dependencies
            // from/to inner classes are not tracked separately. Inner class dependencies are
            // implicitly attributed to the outer class.
            // TODO: Consider whether inner class analysis should be supported as a separate feature
            if (currentClassName.contains("$")) {
                return;
            }
            
            // Handle inner classes: extract outer class name before $
            String classNameForPackage = currentClassName;
            if (currentClassName.contains("$")) {
                classNameForPackage = currentClassName.substring(0, currentClassName.indexOf("$"));
            }
            
            String[] parts = classNameForPackage.split("\\.");
            this.currentSimpleName = parts[parts.length - 1];
            this.currentPackageName = classNameForPackage.substring(0,
                classNameForPackage.lastIndexOf("."));

            // Create ClassInfo
            this.currentClassInfo = new DependencyModel.ClassInfo(
                currentClassName, currentSimpleName, currentPackageName
            );
            model.addClass(currentClassName, currentClassInfo);

            // Add superclass dependency
            if (superName != null && !superName.equals("java/lang/Object")) {
                String superClassName = convertClassName(superName);
                if (!isSelfDependency(superClassName) && !isJavaClass(superClassName)) {
                    currentClassInfo.dependencies.add(superClassName);
                }
            }

            // Add interface dependencies
            if (interfaces != null) {
                for (String iface : interfaces) {
                    String ifaceClassName = convertClassName(iface);
                    if (!isSelfDependency(ifaceClassName) && !isJavaClass(ifaceClassName)) {
                        currentClassInfo.dependencies.add(ifaceClassName);
                    }
                }
            }

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // Register method in class info
            currentClassInfo.addMethod(name, descriptor);
            DependencyModel.MethodInfo methodInfo = currentClassInfo.getMethod(name, descriptor);

            // Return a method visitor to track method calls
            return new MethodCallExtractor(currentClassInfo, methodInfo);
        }

        private String convertClassName(String internalName) {
            return internalName.replace("/", ".");
        }

        private boolean isSelfDependency(String className) {
            return className.equals(currentClassName);
        }

        private boolean isJavaClass(String className) {
            return className.startsWith("java.") || className.startsWith("javax.");
        }
    }

    /**
     * MethodVisitor to extract method calls and field accesses.
     */
    private static class MethodCallExtractor extends MethodVisitor {
        private final DependencyModel.ClassInfo classInfo;
        private final DependencyModel.MethodInfo methodInfo;

        public MethodCallExtractor(DependencyModel.ClassInfo classInfo,
                                   DependencyModel.MethodInfo methodInfo) {
            super(Opcodes.ASM9);
            this.classInfo = classInfo;
            this.methodInfo = methodInfo;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            String ownerClass = owner.replace("/", ".");
            String methodCall = ownerClass + "." + name;

            // Track the call
            if (!ownerClass.startsWith("java.") && !ownerClass.startsWith("javax.")) {
                methodInfo.methodCalls.merge(methodCall, 1, Integer::sum);
                classInfo.dependencies.add(ownerClass);
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
