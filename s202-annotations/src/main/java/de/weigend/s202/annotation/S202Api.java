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
 * Marks a Java package as part of the public API of its enclosing
 * {@link S202Component}. Place this annotation in the package's
 * {@code package-info.java}.
 *
 * <p>Use this when a component's API lives in named sub-packages
 * that the heuristics would not automatically promote — e.g. when
 * the sub-package is neither named {@code api}/{@code ports} nor
 * contains interfaces at the top level.
 *
 * <pre>{@code
 * // com/acme/payment/contract/package-info.java
 * @S202Api
 * package com.acme.payment.contract;
 *
 * import de.weigend.s202.annotation.S202Api;
 * }</pre>
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.CLASS)
public @interface S202Api {
}
