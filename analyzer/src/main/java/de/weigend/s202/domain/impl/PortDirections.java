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
package de.weigend.s202.domain.impl;

import de.weigend.s202.domain.architecture.ArchitectureAnnotations;
import de.weigend.s202.domain.architecture.ArchitectureAnnotations.PortDirection;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which classes are the ports of a hexagon, and on which side they are. The
 * first that applies decides:
 * <ol>
 *   <li>a port marked by the user,</li>
 *   <li>a package named {@code api} (inbound) or {@code spi} (outbound),</li>
 *   <li>below a package named {@code port} or {@code ports} the sub-package that
 *       names a direction, such as {@code port.in} and {@code port.out},</li>
 *   <li>otherwise the place of the implementation: the owner of a port is the
 *       package around {@code port}. What is implemented outside of the owner is
 *       outbound, everything else inbound. Where the owner is the whole system,
 *       nothing is outside, and the port has no direction.</li>
 * </ol>
 */
final class PortDirections {

    private static final Set<String> INBOUND_NAMES =
            Set.of("api", "in", "inbound", "incoming", "input", "driving", "primary");
    private static final Set<String> OUTBOUND_NAMES =
            Set.of("spi", "out", "outbound", "outgoing", "output", "driven", "secondary");

    private PortDirections() {
    }

    /**
     * @param classes       all class names
     * @param effectiveRoot the package below which the system begins
     * @param rawModel      tells who implements what; may be {@code null}
     * @return the direction of every port by class name
     */
    static Map<String, PortDirection> of(Collection<String> classes,
                                         String effectiveRoot,
                                         ArchitectureAnnotations annotations,
                                         DependencyModel rawModel) {
        Map<String, PortDirection> directions = new HashMap<>();
        Map<String, String> ownerByPort = new HashMap<>();
        for (String fqn : classes) {
            ArchitectureAnnotations.PortSpec marked = annotations.explicitPort(fqn);
            String packageFqn = parentOf(fqn);
            String portPackage = portPackageAround(packageFqn);
            if (marked != null) {
                directions.put(fqn, marked.direction() == PortDirection.OUTBOUND
                        ? PortDirection.OUTBOUND : PortDirection.INBOUND);
            } else if (ContractPackages.isApiName(simpleName(packageFqn))) {
                directions.put(fqn, PortDirection.INBOUND);
            } else if (ContractPackages.isSpiName(simpleName(packageFqn))) {
                directions.put(fqn, PortDirection.OUTBOUND);
            } else if (portPackage != null) {
                PortDirection named = namedDirection(portPackage, packageFqn);
                String owner = parentOf(portPackage);
                if (named != null) {
                    directions.put(fqn, named);
                } else if (isWholeSystem(owner, effectiveRoot)) {
                    directions.put(fqn, PortDirection.GENERIC);
                } else {
                    directions.put(fqn, PortDirection.INBOUND);
                    ownerByPort.put(fqn, owner);
                }
            }
        }
        if (rawModel != null && !ownerByPort.isEmpty()) {
            outboundPorts(ownerByPort, rawModel).forEach(fqn -> directions.put(fqn, PortDirection.OUTBOUND));
        }
        return directions;
    }

    /** Whether a package is a contract as a whole: {@code api}, {@code spi}, or {@code port} and all below it. */
    static boolean isContract(String packageFqn) {
        return ContractPackages.isContractName(simpleName(packageFqn)) || portPackageAround(packageFqn) != null;
    }

    /** The ports a class outside of their owner implements. */
    private static Set<String> outboundPorts(Map<String, String> ownerByPort, DependencyModel rawModel) {
        Set<String> outbound = new HashSet<>();
        for (DependencyModel.ClassInfo implementer : rawModel.getAllClasses().values()) {
            for (Map.Entry<String, EnumSet<EdgeKind>> dependency : implementer.dependencyKinds.entrySet()) {
                String owner = ownerByPort.get(dependency.getKey());
                boolean realizes = dependency.getValue().contains(EdgeKind.IMPLEMENTS)
                        || dependency.getValue().contains(EdgeKind.EXTENDS);
                if (owner != null && realizes && !isBelow(implementer.fullName, owner)) {
                    outbound.add(dependency.getKey());
                }
            }
        }
        return outbound;
    }

    private static boolean isWholeSystem(String owner, String effectiveRoot) {
        return owner.isEmpty() || owner.equals(effectiveRoot) || isBelow(effectiveRoot, owner);
    }

    private static PortDirection namedDirection(String portPackage, String packageFqn) {
        if (packageFqn.equals(portPackage)) {
            return null;
        }
        String below = packageFqn.substring(portPackage.length() + 1);
        for (String name : below.toLowerCase(Locale.ROOT).split("\\.")) {
            if (INBOUND_NAMES.contains(name)) {
                return PortDirection.INBOUND;
            }
            if (OUTBOUND_NAMES.contains(name)) {
                return PortDirection.OUTBOUND;
            }
        }
        return null;
    }

    /** The innermost package named {@code port} or {@code ports} that is or contains {@code packageFqn}. */
    private static String portPackageAround(String packageFqn) {
        for (String current = packageFqn; !current.isEmpty(); current = parentOf(current)) {
            if (ContractPackages.isPortName(simpleName(current))) {
                return current;
            }
        }
        return null;
    }

    private static boolean isBelow(String fqn, String packageFqn) {
        return fqn.startsWith(packageFqn + ".");
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private static String parentOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
