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
package de.weigend.s202.reader.impl.java;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.annotation.S202Api;
import de.weigend.s202.annotation.S202Component;
import de.weigend.s202.annotation.S202Package;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.LanguageAnalyzer;
import de.weigend.s202.reader.TypeUse;
import de.weigend.s202.reader.impl.PackageHierarchyBuilder;

import jakarta.inject.Singleton;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

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
 * current working directory or one of its parents, else from the file named by the
 * system property {@code s202.excludedPrefixes}. If neither exists, built-in defaults
 * are used.</p>
 */
@Singleton
public class InputAnalyzer implements LanguageAnalyzer {
    
    private static final String EXCLUDED_PREFIXES_FILE = "excluded-prefixes.txt";

    /** Fallback location of the file when none is found in the working directory or its parents. */
    private static final String EXCLUDED_PREFIXES_PROPERTY = "s202.excludedPrefixes";
    
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

    @Override
    public String displayName() {
        return "Java bytecode";
    }

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
        // Packaged app: the launcher points at the copy shipped next to the jars,
        // because the working directory is wherever the user started the program.
        String configured = System.getProperty(EXCLUDED_PREFIXES_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path shipped = Paths.get(configured);
            if (Files.isRegularFile(shipped)) {
                return shipped;
            }
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
        PackageHierarchyBuilder.buildPackageHierarchy(model);
        return model;
    }

    @Override
    public DependencyModel analyze(List<Path> inputs) throws IOException {
        return analyzeMultiple(inputs.stream()
                .map(Path::toString)
                .toList());
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
        PackageHierarchyBuilder.buildPackageHierarchy(model);
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
        String origin = Paths.get(jarPath).getFileName().toString();
        try (JarFile jarFile = new JarFile(jarPath)) {
            // Nested classes last: they add to their outer class, which must be there by then.
            List<JarEntry> entries = jarFile.stream()
                    .sorted(Comparator.comparing(entry -> entry.getName().contains("$")))
                    .toList();
            for (JarEntry entry : entries) {
                if (!entry.getName().endsWith(".class") || entry.isDirectory()) {
                    continue;
                }

                try {
                    byte[] classBytes = jarFile.getInputStream(entry).readAllBytes();
                    if (entry.getName().endsWith("package-info.class")) {
                        analyzePackageInfo(classBytes, model);
                        continue;
                    } else if (entry.getName().endsWith("module-info.class")) {
                        analyzeModule(entry.getName(), classBytes, model);
                    } else {
                        analyzeClass(entry.getName(), classBytes, model, origin);
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
    private void analyzePackageInfo(byte[] bytecode, DependencyModel model) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(new PackageAnnotationExtractor(model),
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            System.err.println("Error analyzing package-info: " + e.getMessage());
        }
    }

    private void analyzeClass(String classPath, byte[] bytecode, DependencyModel model, String origin) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            if (reader.getClassName().contains("$") && !isNestedClass(reader)) {
                return; // a generated class that only looks like a nested one, e.g. Foo$DI
            }
            DependencyExtractor extractor = new DependencyExtractor(model, origin);
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
     * ASM visitor for module-info.class. Stores exports and opens as raw
     * metadata; consumers decide which descriptor entries are policy-relevant.
     */
    private static class PackageAnnotationExtractor extends ClassVisitor {
        private static final String S202_COMPONENT_DESC = Type.getDescriptor(S202Component.class);
        private static final String S202_API_DESC = Type.getDescriptor(S202Api.class);
        private static final String S202_PACKAGE_DESC = Type.getDescriptor(S202Package.class);
        private final DependencyModel model;
        private String packageFqn;

        PackageAnnotationExtractor(DependencyModel model) {
            super(Opcodes.ASM9);
            this.model = model;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            packageFqn = name.replace('/', '.').replace(".package-info", "");
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (packageFqn == null) return null;
            if (S202_COMPONENT_DESC.equals(descriptor)) {
                model.addComponentAnnotatedPackage(packageFqn);
            } else if (S202_API_DESC.equals(descriptor)) {
                model.addApiAnnotatedPackage(packageFqn);
            } else if (S202_PACKAGE_DESC.equals(descriptor)) {
                model.addPlainPackage(packageFqn);
            }
            return null;
        }
    }

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
     * True for a class that is nested, at whatever depth, in the top-level class
     * its name starts with. The name alone does not tell: generators like to
     * call top-level classes {@code Foo$DI}. The bytecode names the enclosing
     * class of a member class, and that of the visited local or anonymous one.
     */
    private static boolean isNestedClass(ClassReader reader) {
        Map<String, String> enclosing = new HashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private String enclosingOfVisited;

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                enclosingOfVisited = owner;
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                enclosing.put(name, outerName != null ? outerName
                        : name.equals(reader.getClassName()) ? enclosingOfVisited : null);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String current = reader.getClassName();
        if (!enclosing.containsKey(current)) {
            return false;
        }
        while (current != null && enclosing.containsKey(current)) {
            current = enclosing.get(current);
        }
        // Null: inside another anonymous class, whose own enclosing class is not named here.
        return current == null || current.equals(outerClassName(reader.getClassName()));
    }

    /** The class a nested class counts for: {@code com.example.Foo$Bar} → {@code com.example.Foo}. */
    private static String outerClassName(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    /**
     * ASM ClassVisitor to extract dependencies from bytecode. A nested class
     * has no entry of its own in the model; what it depends on is added to its
     * outer class.
     */
    private static class DependencyExtractor extends ClassVisitor {
        private final DependencyModel model;
        /** Where the dependencies go: the visited class, or the outer class of a nested one. */
        private DependencyModel.ClassInfo currentClassInfo;
        /** {@code Inner.} for the members of a nested class, else empty. */
        private String memberPrefix = "";
        private TypeUseRecorder typeUses;

        /** The file the classes come from. */
        private final String origin;

        public DependencyExtractor(DependencyModel model, String origin) {
            super(Opcodes.ASM9);
            this.model = model;
            this.origin = origin;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            // Convert bytecode name (com/example/Class) to source name (com.example.Class)
            String className = convertClassName(name);

            // Skip classes the user excluded via excluded-prefixes.txt — keeps
            // framework-generated wiring (Avaje DI modules, Lombok stubs, etc.)
            // out of the model entirely instead of just out of dep targets.
            if (isExcludedClass(className)) {
                return;
            }

            String outerName = outerClassName(className);
            if (outerName.equals(className)) {
                int packageEnd = className.lastIndexOf('.');
                this.currentClassInfo = new DependencyModel.ClassInfo(className,
                        className.substring(packageEnd + 1), className.substring(0, packageEnd),
                        (access & Opcodes.ACC_INTERFACE) != 0);
                currentClassInfo.setOrigin(origin);
                model.addClass(className, currentClassInfo);
            } else {
                this.currentClassInfo = model.getClass(outerName);
                if (currentClassInfo == null) {
                    return; // the outer class is not part of the analysis
                }
                this.memberPrefix = className.substring(outerName.length() + 1) + ".";
            }
            this.typeUses = new TypeUseRecorder(
                    currentClassInfo, className, memberPrefix, InputAnalyzer::isExcludedClass);
            typeUses.superTypeArguments(signature);

            if (superName != null) {
                addInheritance(className, convertClassName(superName), EdgeKind.EXTENDS);
            }
            if (interfaces != null) {
                for (String iface : interfaces) {
                    addInheritance(className, convertClassName(iface), EdgeKind.IMPLEMENTS);
                }
            }

            super.visit(version, access, name, signature, superName, interfaces);
        }

        private void addInheritance(String className, String superType, EdgeKind kind) {
            String target = outerClassName(superType);
            if (isSelfDependency(target)) {
                return;
            }
            boolean betweenTopLevelClasses = target.equals(superType) && currentClassInfo.fullName.equals(className);
            EdgeKind edgeKind = betweenTopLevelClasses ? kind : EdgeKind.NESTED_INHERITANCE;
            if (isExternalLibraryClass(target)) {
                currentClassInfo.addExternalDependency(target, edgeKind);
                return;
            }
            currentClassInfo.addDependency(target, edgeKind);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (currentClassInfo == null) {
                return null;
            }
            typeUses.field(name, descriptor, signature);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    return typeUses.annotation(memberPrefix + name, null, annotation);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String annotation,
                                                             boolean visible) {
                    return typeUses.annotation(memberPrefix + name, null, annotation);
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
            return currentClassInfo == null ? null : typeUses.annotation(memberPrefix, null, annotation);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String annotation, boolean visible) {
            return visitAnnotation(annotation, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (currentClassInfo == null)
                return null;

            String member = memberPrefix + name;
            typeUses.method(member, descriptor, signature, exceptions);

            currentClassInfo.addMethod(member, descriptor);
            DependencyModel.MethodInfo methodInfo = currentClassInfo.getMethod(member, descriptor);
            return new MethodCallExtractor(currentClassInfo, methodInfo, typeUses);
        }

        private String convertClassName(String internalName) {
            return internalName.replace("/", ".");
        }

        private boolean isSelfDependency(String className) {
            return className.equals(currentClassInfo.fullName);
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
            return isExcludedClass(outerClassName(className));
        }
    }

    /**
     * MethodVisitor to extract method calls and field accesses.
     */
    private static class MethodCallExtractor extends MethodVisitor {
        private final DependencyModel.ClassInfo classInfo;
        private final DependencyModel.MethodInfo methodInfo;
        private final TypeUseRecorder typeUses;

        public MethodCallExtractor(DependencyModel.ClassInfo classInfo,
                                   DependencyModel.MethodInfo methodInfo,
                                   TypeUseRecorder typeUses) {
            super(Opcodes.ASM9);
            this.typeUses = typeUses;
            this.classInfo = classInfo;
            this.methodInfo = methodInfo;
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return typeUses.annotationValues(methodInfo.name, methodInfo.descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
            return typeUses.annotation(methodInfo.name, methodInfo.descriptor, annotation);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String annotation, boolean visible) {
            return visitAnnotation(annotation, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String annotation, boolean visible) {
            return visitAnnotation(annotation, visible);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) { // finally catches everything
                typeUses.typeInstruction(methodInfo, TypeUse.Kind.CATCH, type);
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            typeUses.typeInstruction(methodInfo, TypeUse.Kind.ARRAY, descriptor);
            super.visitMultiANewArrayInsn(descriptor, dimensions);
        }

        @Override
        public void visitLdcInsn(Object value) {
            // Class literals: Foo.class compiles to LDC with a Type constant
            if (value instanceof Type t) {
                typeUses.classLiteral(methodInfo, t);
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            typeUses.typeInstruction(methodInfo, switch (opcode) {
                case Opcodes.NEW -> TypeUse.Kind.NEW;
                case Opcodes.CHECKCAST -> TypeUse.Kind.CAST;
                case Opcodes.INSTANCEOF -> TypeUse.Kind.INSTANCEOF;
                default -> TypeUse.Kind.ARRAY;
            }, type);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            typeUses.fieldAccess(methodInfo, owner, name, descriptor);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // INVOKESPECIAL on <init> is "new T(...)" — distinguish constructor
            // from regular method calls so the UI can show "instantiates" vs "calls".
            addCall(owner, name, descriptor, opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name));
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /**
         * A method or constructor reference ({@code Foo::bar}) calls nothing
         * here, but it names the method it stands for among the arguments. The
         * body of a lambda is a method of the class itself.
         */
        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethod,
                                           Object... bootstrapArguments) {
            for (Object argument : bootstrapArguments) {
                if (argument instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                    addCall(handle.getOwner(), handle.getName(), handle.getDesc(),
                            handle.getTag() == Opcodes.H_NEWINVOKESPECIAL);
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethod, bootstrapArguments);
        }

        /**
         * Records a call unless it goes to the class itself. A call into an
         * excluded library is kept as an external dependency only, without
         * the method.
         */
        private void addCall(String owner, String name, String descriptor, boolean constructor) {
            String ownerClass = owner.replace("/", ".");
            // Map inner classes to their outer class for dependency tracking
            String dependencyClass = outerClassName(ownerClass);
            if (dependencyClass.equals(classInfo.fullName) || dependencyClass.startsWith("[")) {
                return;
            }
            EdgeKind kind = constructor ? EdgeKind.INSTANTIATES : EdgeKind.CALLS;
            if (isExternalLibraryClass(dependencyClass)) {
                classInfo.addExternalDependency(dependencyClass, kind);
                return;
            }
            String methodCall = ownerClass + "." + name;
            methodInfo.methodCalls.merge(methodCall, 1, Integer::sum);
            methodInfo.methodCallDescriptors
                    .computeIfAbsent(methodCall, k -> new HashSet<>())
                    .add(descriptor);
            classInfo.addDependency(dependencyClass, kind);
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
            return isExcludedClass(outerClassName(className));
        }
    }
}
