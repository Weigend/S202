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

import java.util.Locale;
import java.util.Set;

/**
 * The names by which a package declares itself the contract of the package
 * around it: {@code api} (called by others), {@code spi} (implemented by
 * others), and {@code port} or {@code ports}, the word of the hexagonal
 * architecture for both.
 */
final class ContractPackages {

    private static final Set<String> PORT_NAMES = Set.of("port", "ports");

    private ContractPackages() {
    }

    static boolean isContractName(String simpleName) {
        return isApiName(simpleName) || isSpiName(simpleName) || isPortName(simpleName);
    }

    static boolean isApiName(String simpleName) {
        return "api".equals(normalized(simpleName));
    }

    static boolean isSpiName(String simpleName) {
        return "spi".equals(normalized(simpleName));
    }

    static boolean isPortName(String simpleName) {
        return PORT_NAMES.contains(normalized(simpleName));
    }

    private static String normalized(String simpleName) {
        return simpleName == null ? "" : simpleName.toLowerCase(Locale.ROOT);
    }
}
