package de.weigend.s202.reader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Analyzes Java bytecode from JAR files and extracts dependency information.
 * This is the raw analysis layer - NO layer calculation, NO UI dependencies.
 * 
 * <p>External library prefixes are loaded from {@code excluded-prefixes.txt} in the
 * current working directory. If the file is not found, built-in defaults are used.</p>
 */
public class InputAnalyzer {
    
    private static final String EXCLUDED_PREFIXES_FILE = "excluded-prefixes.txt";
    
    /**
     * Minimal default exclusions - only JDK internal classes that are never part of user code.
     * For additional exclusions (JavaFX, test frameworks, etc.), use excluded-prefixes.txt.
     */
    private static final List<String> DEFAULT_EXCLUDED_PREFIXES = List.of(
        "java.",      // Java Standard Library
        "javax.",     // Java Extensions
        "jdk.",       // JDK internals
        "sun.",       // JDK implementation
        "com.sun."    // JDK implementation
    );
    
    private static List<String> excludedPrefixes;
    
    /**
     * Returns the list of excluded class prefixes.
     * Loads from excluded-prefixes.txt if available, otherwise uses defaults.
     */
    public static List<String> getExcludedPrefixes() {
        if (excludedPrefixes == null) {
            excludedPrefixes = loadExcludedPrefixes();
        }
        return excludedPrefixes;
    }
    
    /**
     * Loads excluded prefixes from the configuration file.
     */
    private static List<String> loadExcludedPrefixes() {
        Path configPath = Paths.get(EXCLUDED_PREFIXES_FILE);
        
        if (!Files.exists(configPath)) {
            System.out.println("No " + EXCLUDED_PREFIXES_FILE + " found, using default excluded prefixes.");
            return new ArrayList<>(DEFAULT_EXCLUDED_PREFIXES);
        }
        
        List<String> prefixes = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip empty lines and comments
                if (!line.isEmpty() && !line.startsWith("#")) {
                    prefixes.add(line);
                }
            }
            System.out.println("Loaded " + prefixes.size() + " excluded prefixes from " + EXCLUDED_PREFIXES_FILE);
        } catch (IOException e) {
            System.err.println("Warning: Could not read " + EXCLUDED_PREFIXES_FILE + ": " + e.getMessage());
            System.out.println("Using default excluded prefixes.");
            return new ArrayList<>(DEFAULT_EXCLUDED_PREFIXES);
        }
        
        return prefixes;
    }
    
    /**
     * Reloads the excluded prefixes from the configuration file.
     * Call this method after modifying excluded-prefixes.txt to apply changes.
     */
    public static void reloadExcludedPrefixes() {
        excludedPrefixes = null;
        getExcludedPrefixes();
    }

    /**
     * Analyzes a JAR file and returns raw dependency model.
     */
    public DependencyModel analyze(String jarPath) throws IOException {
        DependencyModel model = new DependencyModel();
        analyzeInto(jarPath, model);
        buildPackageHierarchy(model);
        return model;
    }

    /**
     * Analyzes multiple JAR files and returns a combined dependency model.
     */
    public DependencyModel analyzeMultiple(java.util.List<String> jarPaths) throws IOException {
        DependencyModel model = new DependencyModel();
        for (String jarPath : jarPaths) {
            analyzeInto(jarPath, model);
        }
        buildPackageHierarchy(model);
        return model;
    }

    /**
     * Analyzes a JAR file and adds its classes to an existing model.
     */
    private void analyzeInto(String jarPath, DependencyModel model) throws IOException {
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
            boolean interfaceType = (access & Opcodes.ACC_INTERFACE) != 0;
            this.currentClassInfo = new DependencyModel.ClassInfo(
                currentClassName, currentSimpleName, currentPackageName, interfaceType
            );
            model.addClass(currentClassName, currentClassInfo);

            // Add superclass dependency
            if (superName != null && !superName.equals("java/lang/Object")) {
                String superClassName = convertClassName(superName);
                if (!isSelfDependency(superClassName) && !isExternalLibraryClass(superClassName)) {
                    currentClassInfo.dependencies.add(superClassName);
                }
            }

            // Add interface dependencies
            if (interfaces != null) {
                for (String iface : interfaces) {
                    String ifaceClassName = convertClassName(iface);
                    if (!isSelfDependency(ifaceClassName) && !isExternalLibraryClass(ifaceClassName)) {
                        currentClassInfo.dependencies.add(ifaceClassName);
                    }
                }
            }

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // Skip if this is an inner class (currentClassInfo is null)
            if (currentClassInfo == null) {
                return null;
            }
            
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

        /**
         * Checks if a class is from external libraries (JDK, JavaFX, frameworks, etc.)
         * Prefixes are loaded from excluded-prefixes.txt in the working directory.
         */
        private boolean isExternalLibraryClass(String className) {
            // Skip array types (e.g., "[Lcom.example.Foo;")
            if (className.startsWith("[")) {
                return true;
            }
            
            // Handle inner classes - get the outer class name
            String outerClassName = className.contains("$") 
                ? className.substring(0, className.indexOf('$')) 
                : className;
            
            // Check against loaded prefixes
            for (String prefix : getExcludedPrefixes()) {
                if (outerClassName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
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
            
            // Map inner classes to their outer class for dependency tracking
            String dependencyClass = getOuterClassName(ownerClass);
            String methodCall = ownerClass + "." + name;

            // Track the call - filter out external library classes and self-references
            if (!isExternalLibraryClass(dependencyClass) && !dependencyClass.equals(classInfo.fullName)) {
                methodInfo.methodCalls.merge(methodCall, 1, Integer::sum);
                classInfo.dependencies.add(dependencyClass);
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
        
        /**
         * Gets the outer class name for inner classes.
         * e.g., "com.example.Foo$Bar" -> "com.example.Foo"
         */
        private String getOuterClassName(String className) {
            return className.contains("$") 
                ? className.substring(0, className.indexOf('$')) 
                : className;
        }
        
        /**
         * Checks if a class is from external libraries (JDK, JavaFX, frameworks, etc.)
         * Prefixes are loaded from excluded-prefixes.txt in the working directory.
         */
        private boolean isExternalLibraryClass(String className) {
            // Skip array types (e.g., "[Lcom.example.Foo;")
            if (className.startsWith("[")) {
                return true;
            }
            
            // Handle inner classes - get the outer class name
            String outerClassName = className.contains("$") 
                ? className.substring(0, className.indexOf('$')) 
                : className;
            
            // Check against loaded prefixes
            for (String prefix : getExcludedPrefixes()) {
                if (outerClassName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
