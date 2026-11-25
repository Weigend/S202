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
 * One place where a class refers to another without calling it: where an
 * {@link EdgeKind#USES} relation comes from.
 *
 * @param member     the field or method of the referring class it is found at;
 *                   empty for the class itself, {@code Inner.} for a nested one
 * @param descriptor descriptor of the method; {@code null} for a field or class
 * @param detail     the accessed field ({@code Owner.field}) of a
 *                   {@link Kind#FIELD_ACCESS}; {@code null} otherwise
 */
public record TypeUse(Kind kind, String member, String descriptor, String detail) {

    /** In display order. */
    public enum Kind {
        /** Type of a declared field, type arguments included. */
        FIELD,
        PARAMETER,
        RETURN,
        THROWS,
        CATCH,
        /** Bound of a type parameter. */
        TYPE_PARAMETER,
        /** Type argument of a supertype: {@code class A implements Comparable<B>}. */
        TYPE_ARGUMENT,
        ANNOTATION,
        /** A field of the other class, or one of its type, is read or written. */
        FIELD_ACCESS,
        CAST,
        INSTANCEOF,
        ARRAY,
        CLASS_LITERAL,
        /** {@code new T(...)}: accounted for by the constructor call that follows. */
        NEW
    }
}
