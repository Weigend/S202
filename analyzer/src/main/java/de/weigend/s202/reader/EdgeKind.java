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

/**
 * Classification of a class-to-class dependency edge as captured by the
 * bytecode reader. A single (source, target) pair can carry multiple kinds —
 * e.g. {@code A extends B} and {@code A} also calls a method on {@code B}.
 */
public enum EdgeKind {
    // Order is also the natural display order — structural relationships
    // (extends/implements) before construction, calls last because they
    // expand into one row per method name.
    /** {@code class A extends B} */
    EXTENDS,
    /** {@code class A implements B} (or interface extension) */
    IMPLEMENTS,
    /** Source-level module import — especially relevant for Python module dependencies. */
    IMPORTS,
    /** Constructor invocation — {@code new T(...)} (INVOKESPECIAL on {@code <init>}). */
    INSTANTIATES,
    /** Method invocation on the target class (excluding constructor calls). */
    CALLS,
    /** Structural reference: field type, method parameter/return type, cast target, or field owner. */
    USES;

    /** Lower-case label suitable for compact UI rendering ("calls", "extends", …). */
    public String label() {
        return name().toLowerCase();
    }
}
