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
package de.weigend.s202.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java package as a component root in S202's component architecture view.
 * Place this annotation in the package's {@code package-info.java}.
 *
 * <p>A package with a direct sub-package {@code api} or {@code spi} is a component
 * without it. Use it for a component that has no such package: all of its classes
 * are private then, unless {@link S202Api} says otherwise. Components may be nested.
 *
 * <pre>{@code
 * // de/weigend/s202/reader/package-info.java
 * @S202Component(displayName = "Reader")
 * package de.weigend.s202.reader;
 *
 * import de.weigend.s202.annotation.S202Component;
 * }</pre>
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.CLASS)
public @interface S202Component {

    /** Optional stable identifier. Defaults to the package simple name. */
    String id() default "";

    /** Optional human-readable display name shown in the UI. */
    String displayName() default "";
}
