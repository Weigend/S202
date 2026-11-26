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
package de.weigend.s202.domain;

import de.weigend.s202.reader.DependencyModel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The classes of an analysis ordered by the files they were read from instead
 * of by their packages: the physical structure beside the logical one.
 *
 * <p>Nothing of the analysis has to know a second hierarchy. Every class gets
 * the file it comes from as the first part of its name, so that
 * {@code com.shop.order.Order} from {@code shop-order-1.0.jar} becomes
 * {@code shop-order-1․0․jar.com.shop.order.Order}. The dots of the file name
 * are replaced by {@link #DOT}, which looks like one but does not separate
 * packages; the file is a container like a top-level package, and everything
 * that works on packages - levels, tangles, the matrix, the panels - works
 * on files.
 *
 * <p>Where a name is shown to the user, {@link #shown} takes the file away
 * again.
 */
public final class PhysicalHierarchy {

    /** Stands for the dots of a file name: ONE DOT LEADER, which looks like a full stop. */
    public static final char DOT = '․';

    /** The file of classes whose origin was not recorded. */
    static final String UNKNOWN = "unknown" + DOT + "origin";

    /** A file in a name, followed by the dot that separates it from the package. */
    private static final Pattern FILE_PREFIX = Pattern.compile("[^\\s.,;:()\\[\\]<>→↑]*" + DOT + "[^\\s.,;:\\[\\]<>→↑]*\\.");

    private final RenamedModel renamed;

    private PhysicalHierarchy(DependencyModel logical) {
        renamed = new RenamedModel(logical, info -> file(info) + "." + info.fullName);
    }

    /** The classes of {@code logical} under the files they were read from; a new model. */
    public static PhysicalHierarchy of(DependencyModel logical) {
        return new PhysicalHierarchy(logical);
    }

    /** The number of files the classes come from; a physical view says something from two on. */
    public static int files(DependencyModel logical) {
        Set<String> files = new HashSet<>();
        logical.getAllClasses().values().forEach(info -> files.add(file(info)));
        return files.size();
    }

    /** The model with the files as the outermost containers. */
    public DependencyModel model() {
        return renamed.model();
    }

    /** The name of a class of the analysis in this hierarchy; other names stay as they are. */
    public String toPhysical(String logical) {
        return renamed.rename(logical);
    }

    /** The name of an element of this hierarchy in the analysis: without its file. */
    public static String toLogical(String physical) {
        if (physical == null) {
            return null;
        }
        int dot = physical.indexOf('.');
        String first = dot < 0 ? physical : physical.substring(0, dot);
        if (first.indexOf(DOT) < 0) {
            return physical;
        }
        return dot < 0 ? fileName(physical) : physical.substring(dot + 1);
    }

    /** Whether the name is that of a file, the outermost container of this hierarchy. */
    public static boolean isFile(String name) {
        return name != null && name.indexOf('.') < 0 && name.indexOf(DOT) >= 0;
    }

    /** The name of the file as it is called, with its dots. */
    public static String fileName(String name) {
        return name == null ? null : name.replace(DOT, '.');
    }

    /**
     * Text for the user: every name in it without its file, a file alone by
     * its name. Text without such names stays as it is. Panels use
     * {@link ShownNames#shown}, which knows every order of the classes.
     */
    public static String shown(String text) {
        if (text == null || text.indexOf(DOT) < 0) {
            return text;
        }
        return fileName(FILE_PREFIX.matcher(text).replaceAll(""));
    }

    /**
     * The packages that hold classes in more than one file - split packages,
     * which the module system of Java does not allow.
     *
     * @param packagesWithClasses packages of this hierarchy that hold classes
     * @return each of them that is split → the other files its package has classes in
     */
    public static Map<String, Set<String>> split(java.util.Collection<String> packagesWithClasses) {
        Map<String, Set<String>> filesOf = new HashMap<>();
        for (String pkg : packagesWithClasses) {
            int dot = pkg.indexOf('.');
            if (dot > 0 && isFile(pkg.substring(0, dot))) {
                filesOf.computeIfAbsent(pkg.substring(dot + 1), k -> new TreeSet<>()).add(pkg.substring(0, dot));
            }
        }
        Map<String, Set<String>> split = new HashMap<>();
        filesOf.forEach((logical, files) -> {
            if (files.size() > 1) {
                for (String file : files) {
                    Set<String> others = new TreeSet<>();
                    files.stream().filter(other -> !other.equals(file)).forEach(other -> others.add(fileName(other)));
                    split.put(file + "." + logical, others);
                }
            }
        });
        return split;
    }

    private static String file(DependencyModel.ClassInfo info) {
        String origin = info.getOrigin();
        return origin == null || origin.isBlank() ? UNKNOWN : origin.replace('.', DOT);
    }
}
