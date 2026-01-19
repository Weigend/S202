package de.weigend.s202.io;

import de.weigend.s202.analysis.BytecodeAnalyzer;
import de.weigend.s202.model.JavaClass;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * Loads and analyzes Java classes from JAR files.
 */
public class JarLoader {
    private final BytecodeAnalyzer analyzer;

    public JarLoader() {
        this.analyzer = new BytecodeAnalyzer();
    }

    /**
     * Loads all classes from a JAR file and analyzes them.
     *
     * @param jarFile The JAR file to load
     * @return Map of class name to JavaClass
     * @throws IOException if reading the JAR fails
     */
    public Map<String, JavaClass> loadJar(File jarFile) throws IOException {
        Objects.requireNonNull(jarFile, "jarFile cannot be null");
        
        if (!jarFile.exists() || !jarFile.getName().endsWith(".jar")) {
            throw new IllegalArgumentException("File must be a valid JAR file");
        }

        Map<String, JavaClass> classes = new HashMap<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    try {
                        String className = extractClassName(entry.getName());
                        InputStream inputStream = jar.getInputStream(entry);
                        JavaClass javaClass = analyzer.analyzeClass(className, inputStream);
                        classes.put(className, javaClass);
                    } catch (IOException e) {
                        System.err.println("Failed to analyze class: " + entry.getName() + " - " + e.getMessage());
                    }
                }
            }
        }

        return classes;
    }

    /**
     * Loads classes from a directory containing .class files.
     *
     * @param directory The directory to scan
     * @param packagePrefix Package prefix to filter (e.g., "java.lang" to analyze only that package)
     * @return Map of class name to JavaClass
     * @throws IOException if reading files fails
     */
    public Map<String, JavaClass> loadDirectory(File directory, String packagePrefix) throws IOException {
        Objects.requireNonNull(directory, "directory cannot be null");
        Objects.requireNonNull(packagePrefix, "packagePrefix cannot be null");

        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("File must be a directory");
        }

        Map<String, JavaClass> classes = new HashMap<>();
        loadClassesRecursive(directory, "", classes, packagePrefix);
        return classes;
    }

    private void loadClassesRecursive(File dir, String packagePath, Map<String, JavaClass> classes, String packagePrefix) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String newPackagePath = packagePath.isEmpty() 
                    ? file.getName() 
                    : packagePath + "." + file.getName();
                loadClassesRecursive(file, newPackagePath, classes, packagePrefix);
            } else if (file.getName().endsWith(".class")) {
                String className = packagePath.isEmpty() 
                    ? file.getName().replace(".class", "")
                    : packagePath + "." + file.getName().replace(".class", "");

                if (className.startsWith(packagePrefix)) {
                    try {
                        JavaClass javaClass = analyzer.analyzeClass(className, new java.io.FileInputStream(file));
                        classes.put(className, javaClass);
                    } catch (IOException e) {
                        System.err.println("Failed to analyze class: " + className + " - " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Converts JAR entry path to class name.
     * E.g., "java/lang/String.class" → "java.lang.String"
     */
    private String extractClassName(String entryName) {
        return entryName
            .replace("/", ".")
            .replace(".class", "");
    }

    /**
     * Returns list of class files in a JAR.
     */
    public List<String> listClasses(File jarFile) throws IOException {
        Objects.requireNonNull(jarFile, "jarFile cannot be null");

        List<String> classes = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile)) {
            jar.entries().asIterator().forEachRemaining(entry -> {
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    classes.add(extractClassName(entry.getName()));
                }
            });
        }

        return classes.stream().sorted().collect(Collectors.toList());
    }
}
