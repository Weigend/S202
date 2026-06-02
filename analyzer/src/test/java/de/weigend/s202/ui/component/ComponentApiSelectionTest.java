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
package de.weigend.s202.ui.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentApiSelectionTest {

    @Test
    void packageIncludeAppliesToContainedClasses() {
        ComponentApiSelection selection = new ComponentApiSelection();

        selection.include("com.acme.payment.api");

        assertTrue(selection.explicitDecision("com.acme.payment.api.PaymentPort"));
        assertTrue(selection.explicitDecision("com.acme.payment.api.dto.PaymentDto"));
        assertNull(selection.explicitDecision("com.acme.payment.internal.PaymentService"));
    }

    @Test
    void moreSpecificExcludeWinsOverParentInclude() {
        ComponentApiSelection selection = new ComponentApiSelection();

        selection.include("com.acme.payment");
        selection.exclude("com.acme.payment.internal.PaymentService");

        assertTrue(selection.explicitDecision("com.acme.payment.PaymentApi"));
        assertFalse(selection.explicitDecision("com.acme.payment.internal.PaymentService"));
    }

    @Test
    void parentDecisionClearsStaleDescendantDecisions() {
        ComponentApiSelection selection = new ComponentApiSelection();

        selection.include("com.acme.payment");
        selection.exclude("com.acme.payment.internal.PaymentService");
        selection.include("com.acme.payment");

        assertTrue(selection.explicitDecision("com.acme.payment.internal.PaymentService"));
    }
}
