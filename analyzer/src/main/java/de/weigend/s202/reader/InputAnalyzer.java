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
package de.weigend.s202.reader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

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
    private static List<Predicate<String>> exclusionMatchers;

    public record AnalysisProgress(String jarPath, String classEntryName, int processedClasses, int totalClasses) {}

    /**
     * Returns the list of excluded class prefixes.
     * Loads from excluded-prefixes.txt if available, otherwise uses defaults.
     *
     * <p>Entries containing {@code *} or {@code ?} are treated as glob
     * patterns (matched against the full class FQN); entries without
     * wildcards keep the historical prefix-match semantics.
     */
    public static List<String> getExcludedPrefixes() {
        if (excludedPrefixes == null) {
            excludedPrefixes = loadExcludedPrefixes();
            exclusionMatchers = compileMatchers(excludedPrefixes);
        }
        return excludedPrefixes;
    }

    /**
     * Returns true if {@code className} is matched by any configured
     * exclusion entry. Wildcards ({@code *}, {@code ?}) match the full
     * class FQN; non-wildcard entries match as prefixes — same semantics
     * the analyzer has always used for {@code java.}, {@code javafx.},
     * etc.
     */
    public static boolean isExcludedClass(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        if (exclusionMatchers == null) {
            getExcludedPrefixes();
        }
        for (Predicate<String> matcher : exclusionMatchers) {
            if (matcher.test(className)) {
                return true;
            }
        }
        return false;
    }

    private static List<Predicate<String>> compileMatchers(List<String> entries) {
        List<Predicate<String>> compiled = new ArrayList<>(entries.size());
        for (String entry : entries) {
            compiled.add(compileMatcher(entry));
        }
        return compiled;
    }

    private static Predicate<String> compileMatcher(String entry) {
        if (entry.indexOf('*') < 0 && entry.indexOf('?') < 0) {
            return className -> className.startsWith(entry);
        }
        Pattern regex = Pattern.compile(globToRegex(entry));
        return className -> regex.matcher(className).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.toString();
    }
    
    /**
     * Loads excluded prefixes from the configuration file. Searches the
     * current working directory first, then walks up its parent chain —
     * lets users launch the analyzer from any sub-directory (e.g. a
     * Maven module dir) and still pick up a project-root config.
     */
    private static List<String> loadExcludedPrefixes() {
        Path configPath = findConfigFile();

        if (configPath == null) {
            System.out.println("No " + EXCLUDED_PREFIXES_FILE + " found in CWD or any parent, using default excluded prefixes.");
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
            System.out.println("Loaded " + prefixes.size() + " excluded prefixes from " + configPath);
        } catch (IOException e) {
            System.err.println("Warning: Could not read " + EXCLUDED_PREFIXES_FILE + ": " + e.getMessage());
            System.out.println("Using default excluded prefixes.");
            return new ArrayList<>(DEFAULT_EXCLUDED_PREFIXES);
        }
        
        return prefixes;
    }

    private static Path findConfigFile() {
        Path candidate = Paths.get(EXCLUDED_PREFIXES_FILE).toAbsolutePath();
        Path dir = candidate.getParent();
        while (dir != null) {
            Path here = dir.resolve(EXCLUDED_PREFIXES_FILE);
            if (Files.exists(here)) {
                return here;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Reloads the excluded prefixes from the configuration file.
     * Call this method after modifying excluded-prefixes.txt to apply changes.
     */
    public static void reloadExcludedPrefixes() {
        excludedPrefixes = null;
        exclusionMatchers = null;
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
        return analyzeMultiple(jarPaths, progress -> {});
    }

    /**
     * Analyzes multiple JAR files and reports bytecode-reading progress without
     * introducing a dependency on any UI framework.
     */
    public DependencyModel analyzeMultiple(java.util.List<String> jarPaths,
                                           Consumer<AnalysisProgress> progressConsumer) throws IOException {
        DependencyModel model = new DependencyModel();
        Consumer<AnalysisProgress> progress = progressConsumer != null ? progressConsumer : ignored -> {};
        int totalClasses = countClassEntries(jarPaths);
        progress.accept(new AnalysisProgress(null, null, 0, totalClasses));

        int processedClasses = 0;
        for (String jarPath : jarPaths) {
            processedClasses = analyzeInto(jarPath, model, progress, processedClasses, totalClasses);
        }
        buildPackageHierarchy(model);
        return model;
    }

    /**
     * Analyzes a JAR file and adds its classes to an existing model.
     */
    private void analyzeInto(String jarPath, DependencyModel model) throws IOException {
        analyzeInto(jarPath, model, progress -> {}, 0, countClassEntries(List.of(jarPath)));
    }

    private int analyzeInto(String jarPath, DependencyModel model, Consumer<AnalysisProgress> progress,
                            int processedClasses, int totalClasses) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.isDirectory()
                        || entry.getName().endsWith("package-info.class")) {
                    continue;
                }

                try {
                    byte[] classBytes = jarFile.getInputStream(entry).readAllBytes();
                    if (entry.getName().endsWith("module-info.class")) {
                        analyzeModule(entry.getName(), classBytes, model);
                    } else {
                        analyzeClass(entry.getName(), classBytes, model);
                        processedClasses++;
                        progress.accept(new AnalysisProgress(jarPath, entry.getName(), processedClasses, totalClasses));
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Could not analyze " + entry.getName() + ": " + e.getMessage());
                }
            }
        }
        return processedClasses;
    }

    private int countClassEntries(List<String> jarPaths) throws IOException {
        int total = 0;
        for (String jarPath : jarPaths) {
            try (JarFile jarFile = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.isDirectory()
                            && !entry.getName().endsWith("package-info.class")
                            && !entry.getName().endsWith("module-info.class")) {
                        total++;
                    }
                }
            }
        }
        return total;
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
     * Reads Java Platform Module System descriptors without adding module-info
     * as a normal class. Module exports are architectural metadata, not
     * dependency edges.
     */
    private void analyzeModule(String classPath, byte[] bytecode, DependencyModel model) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            ModuleDescriptorExtractor extractor = new ModuleDescriptorExtractor(model);
            reader.accept(extractor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            System.err.println("Error analyzing module descriptor " + classPath + ": " + e.getMessage());
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
     * ASM visitor for module-info.class. Stores exports and opens as raw
     * metadata; consumers decide which descriptor entries are policy-relevant.
     */
    private static class ModuleDescriptorExtractor extends ClassVisitor {
        private final DependencyModel model;

        ModuleDescriptorExtractor(DependencyModel model) {
            super(Opcodes.ASM9);
            this.model = model;
        }

        @Override
        public ModuleVisitor visitModule(String name, int access, String version) {
            DependencyModel.ModuleInfo moduleInfo = model.getOrCreateModule(name, version);
            return new ModuleVisitor(Opcodes.ASM9) {
                @Override
                public void visitExport(String packaze, int access, String... modules) {
                    moduleInfo.addExportedPackage(convertPackageName(packaze), moduleTargets(modules));
                }

                @Override
                public void visitOpen(String packaze, int access, String... modules) {
                    moduleInfo.addOpenedPackage(convertPackageName(packaze), moduleTargets(modules));
                }
            };
        }

        private static String convertPackageName(String internalName) {
            return internalName == null ? "" : internalName.replace("/", ".");
        }

        private static Set<String> moduleTargets(String[] modules) {
            if (modules == null || modules.length == 0) {
                return Set.of();
            }
            Set<String> result = new LinkedHashSet<>();
            for (String module : modules) {
                if (module != null && !module.isBlank()) {
                    result.add(module);
                }
            }
            return result;
        }
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

            // Skip classes the user excluded via excluded-prefixes.txt — keeps
            // framework-generated wiring (Avaje DI modules, Lombok stubs, etc.)
            // out of the model entirely instead of just out of dep targets.
            if (isExcludedClass(currentClassName)) {
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
                    currentClassInfo.addDependency(superClassName, EdgeKind.EXTENDS);
                }
            }

            // Add interface dependencies
            if (interfaces != null) {
                for (String iface : interfaces) {
                    String ifaceClassName = convertClassName(iface);
                    if (!isSelfDependency(ifaceClassName) && !isExternalLibraryClass(ifaceClassName)) {
                        currentClassInfo.addDependency(ifaceClassName, EdgeKind.IMPLEMENTS);
                    }
                }
            }

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (currentClassInfo != null) {
                addTypeRefs(descriptor);
                addSignatureRefs(signature);
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (currentClassInfo == null)
                return null;

            // Parameter and return types from descriptor + generic types from signature
            addTypeRefs(descriptor);
            addSignatureRefs(signature);

            currentClassInfo.addMethod(name, descriptor);
            DependencyModel.MethodInfo methodInfo = currentClassInfo.getMethod(name, descriptor);
            return new MethodCallExtractor(currentClassInfo, methodInfo);
        }

        /** Adds a USES edge for every non-primitive, non-array-of-primitive class in {@code descriptor}. */
        private void addTypeRefs(String descriptor) {
            if (descriptor == null || currentClassInfo == null) return;
            try {
                Type type = Type.getType(descriptor);
                addTypeRef(type);
            } catch (IllegalArgumentException e) {
                // method descriptor — parse args + return separately
                try {
                    for (Type arg : Type.getArgumentTypes(descriptor)) addTypeRef(arg);
                    addTypeRef(Type.getReturnType(descriptor));
                } catch (IllegalArgumentException ignored) { }
            }
        }

        private void addTypeRef(Type type) {
            if (type == null) return;
            while (type.getSort() == Type.ARRAY) type = type.getElementType();
            if (type.getSort() != Type.OBJECT) return;
            String className = type.getClassName();
            if (!isSelfDependency(className) && !isExternalLibraryClass(className))
                currentClassInfo.addDependency(className, EdgeKind.USES);
        }

        /** Visits a generic signature string and adds every referenced class. */
        private void addSignatureRefs(String signature) {
            if (signature == null || currentClassInfo == null) return;
            try {
                new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitClassType(String name) {
                        String className = name.replace("/", ".");
                        if (!isSelfDependency(className) && !isExternalLibraryClass(className))
                            currentClassInfo.addDependency(className, EdgeKind.USES);
                    }
                });
            } catch (Exception ignored) { }
        }

        private String convertClassName(String internalName) {
            return internalName.replace("/", ".");
        }

        private boolean isSelfDependency(String className) {
            return className.equals(currentClassName);
        }

        /**
         * Checks if a class is from external libraries (JDK, JavaFX, frameworks, etc.)
         * or matches a user-supplied glob pattern. Entries are loaded from
         * {@code excluded-prefixes.txt}; wildcards ({@code *}, {@code ?}) are
         * matched against the full class FQN, plain entries as prefixes.
         */
        private boolean isExternalLibraryClass(String className) {
            if (className.startsWith("[")) {
                return true;
            }
            String outerClassName = className.contains("$")
                ? className.substring(0, className.indexOf('$'))
                : className;
            return isExcludedClass(outerClassName);
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
        public void visitLdcInsn(Object value) {
            // Class literals: Foo.class compiles to LDC with a Type constant
            if (value instanceof Type t) {
                if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
                    Type elem = t;
                    while (elem.getSort() == Type.ARRAY) elem = elem.getElementType();
                    if (elem.getSort() == Type.OBJECT) {
                        String className = getOuterClassName(elem.getClassName());
                        if (!isExternalLibraryClass(className) && !className.equals(classInfo.fullName))
                            classInfo.addDependency(className, EdgeKind.USES);
                    }
                }
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // NEW, CHECKCAST, INSTANCEOF, ANEWARRAY — unwrap array descriptors to element class
            String className;
            if (type.startsWith("[")) {
                try {
                    Type t = Type.getType(type);
                    while (t.getSort() == Type.ARRAY) t = t.getElementType();
                    if (t.getSort() != Type.OBJECT) { super.visitTypeInsn(opcode, type); return; }
                    className = getOuterClassName(t.getClassName());
                } catch (IllegalArgumentException ignored) { super.visitTypeInsn(opcode, type); return; }
            } else {
                className = getOuterClassName(type.replace("/", "."));
            }
            if (!isExternalLibraryClass(className) && !className.equals(classInfo.fullName))
                classInfo.addDependency(className, EdgeKind.USES);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // GETSTATIC, PUTSTATIC, GETFIELD, PUTFIELD — track owner AND field type
            String ownerClass = getOuterClassName(owner.replace("/", "."));
            if (!isExternalLibraryClass(ownerClass) && !ownerClass.equals(classInfo.fullName))
                classInfo.addDependency(ownerClass, EdgeKind.USES);
            try {
                Type fieldType = Type.getType(descriptor);
                while (fieldType.getSort() == Type.ARRAY) fieldType = fieldType.getElementType();
                if (fieldType.getSort() == Type.OBJECT) {
                    String typeClass = getOuterClassName(fieldType.getClassName());
                    if (!isExternalLibraryClass(typeClass) && !typeClass.equals(classInfo.fullName))
                        classInfo.addDependency(typeClass, EdgeKind.USES);
                }
            } catch (IllegalArgumentException ignored) { }
            super.visitFieldInsn(opcode, owner, name, descriptor);
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
                methodInfo.methodCallDescriptors
                        .computeIfAbsent(methodCall, k -> new HashSet<>())
                        .add(descriptor);
                // INVOKESPECIAL on <init> is "new T(...)" — distinguish constructor
                // from regular method calls so the UI can show "instantiates" vs "calls".
                EdgeKind kind = (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name))
                        ? EdgeKind.INSTANTIATES
                        : EdgeKind.CALLS;
                classInfo.addDependency(dependencyClass, kind);
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
         * or matches a user-supplied glob pattern. Entries are loaded from
         * {@code excluded-prefixes.txt}; wildcards ({@code *}, {@code ?}) are
         * matched against the full class FQN, plain entries as prefixes.
         */
        private boolean isExternalLibraryClass(String className) {
            if (className.startsWith("[")) {
                return true;
            }
            String outerClassName = className.contains("$")
                ? className.substring(0, className.indexOf('$'))
                : className;
            return isExcludedClass(outerClassName);
        }
    }
}
