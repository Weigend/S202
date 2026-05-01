package de.weigend.s202.reader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Walks a Maven (multi-module) project rooted at a {@code pom.xml} and returns
 * the list of analyzable JAR files produced by {@code mvn package}. Modules
 * are discovered by following {@code <modules><module>…</module></modules>}
 * declarations recursively. Modules with {@code <packaging>pom</packaging>}
 * do not produce a JAR and are skipped.
 *
 * <p>The scanner is purely filesystem-based — it does not invoke Maven and
 * does not parse dependency graphs. It assumes the project has already been
 * built, and reports any module whose {@code target/} directory is empty
 * via {@link Result#missingArtifactModules()} so the caller can prompt the
 * user to run {@code mvn package} first.</p>
 */
public final class MavenProjectScanner {

    /**
     * A scan result carrying the resolved JAR list plus diagnostics about
     * modules that look correct but have no built artifact yet.
     *
     * @param jars                 JARs to analyze, in module-traversal order
     * @param missingArtifactModules module directories (relative to the root)
     *                              that declared a JAR packaging but contain
     *                              no JAR — typically "not built yet"
     * @param scannedModuleCount   total number of modules visited (incl. POM-only)
     */
    public record Result(List<File> jars,
                         List<String> missingArtifactModules,
                         int scannedModuleCount) {}

    /**
     * Scan starting from {@code projectRoot}, which must contain a
     * {@code pom.xml}.
     *
     * @throws IOException              if the root has no pom.xml or a child
     *                                  pom.xml cannot be read
     * @throws IllegalArgumentException if the root is not a directory
     */
    public Result scan(File projectRoot) throws IOException {
        if (projectRoot == null || !projectRoot.isDirectory()) {
            throw new IllegalArgumentException("Maven project root must be a directory: " + projectRoot);
        }
        File rootPom = new File(projectRoot, "pom.xml");
        if (!rootPom.isFile()) {
            throw new IOException("No pom.xml found in " + projectRoot.getAbsolutePath());
        }

        Set<File> jars = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        int[] moduleCount = { 0 };
        // Visited set keeps us safe against cyclic <module> declarations
        // (rare but legal to author by mistake — without it we'd recurse
        // forever).
        Set<File> visited = new LinkedHashSet<>();
        scanModule(projectRoot, projectRoot, jars, missing, moduleCount, visited);

        return new Result(new ArrayList<>(jars), Collections.unmodifiableList(missing), moduleCount[0]);
    }

    private void scanModule(File moduleDir, File rootDir,
                            Set<File> jars, List<String> missing,
                            int[] moduleCount, Set<File> visited) throws IOException {
        File canonical;
        try {
            canonical = moduleDir.getCanonicalFile();
        } catch (IOException e) {
            canonical = moduleDir.getAbsoluteFile();
        }
        if (!visited.add(canonical)) {
            return;
        }
        moduleCount[0]++;

        File pom = new File(moduleDir, "pom.xml");
        if (!pom.isFile()) {
            // Declared as a module but the directory has no pom — record and move on.
            missing.add(relativise(moduleDir, rootDir) + " (missing pom.xml)");
            return;
        }

        Document doc = parsePom(pom);
        Element project = doc.getDocumentElement();

        boolean isPomPackaging = "pom".equalsIgnoreCase(firstChildText(project, "packaging"));
        if (!isPomPackaging) {
            collectJarsFromTarget(moduleDir, jars, missing, rootDir);
        }

        // Recurse into <modules>
        for (Element modules : childElements(project, "modules")) {
            for (Element module : childElements(modules, "module")) {
                String relPath = textOf(module);
                if (relPath == null || relPath.isBlank()) {
                    continue;
                }
                File childDir = new File(moduleDir, relPath.trim());
                if (!childDir.isDirectory()) {
                    missing.add(relativise(childDir, rootDir) + " (module directory not found)");
                    continue;
                }
                scanModule(childDir, rootDir, jars, missing, moduleCount, visited);
            }
        }
    }

    private void collectJarsFromTarget(File moduleDir, Set<File> jars,
                                       List<String> missing, File rootDir) {
        File target = new File(moduleDir, "target");
        if (!target.isDirectory()) {
            missing.add(relativise(moduleDir, rootDir));
            return;
        }
        File[] files = target.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null || files.length == 0) {
            missing.add(relativise(moduleDir, rootDir));
            return;
        }
        boolean addedAny = false;
        for (File jar : files) {
            if (isAnalyzableJar(jar)) {
                jars.add(jar);
                addedAny = true;
            }
        }
        if (!addedAny) {
            missing.add(relativise(moduleDir, rootDir));
        }
    }

    /**
     * A JAR counts as the module's main artifact when it is not a sources /
     * javadoc / tests classifier and is not a Maven-shade backup.
     */
    static boolean isAnalyzableJar(File jar) {
        String name = jar.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar")) {
            return false;
        }
        if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")
                || name.endsWith("-tests.jar") || name.endsWith("-test-sources.jar")) {
            return false;
        }
        // maven-shade-plugin renames the unshaded artifact to original-*.jar
        // and Spring Boot's repackage does the same. Skip both.
        return !name.startsWith("original-");
    }

    private static Document parsePom(File pom) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Don't fetch the Maven 4.0.0 XSD on every parse; we only need
            // structure, not validation. Namespace-aware off so we can match
            // <modules> directly without dealing with the POM namespace prefix.
            dbf.setNamespaceAware(false);
            dbf.setValidating(false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(pom);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Could not parse " + pom.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static String firstChildText(Element parent, String name) {
        List<Element> matches = childElements(parent, name);
        if (matches.isEmpty()) {
            return null;
        }
        return textOf(matches.get(0));
    }

    private static String textOf(Element e) {
        String text = e.getTextContent();
        return text == null ? null : text.trim();
    }

    private static String relativise(File child, File root) {
        try {
            return root.toPath().toAbsolutePath().relativize(child.toPath().toAbsolutePath()).toString();
        } catch (IllegalArgumentException ex) {
            return child.getAbsolutePath();
        }
    }
}
