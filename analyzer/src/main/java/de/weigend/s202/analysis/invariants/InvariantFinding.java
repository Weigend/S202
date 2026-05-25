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
package de.weigend.s202.analysis.invariants;

/**
 * One concrete invariant violation, ready to drop into a reproducer test.
 * Ported from the Software City C# project (LayoutInvariantChecker.cs):
 * since the 2D layout uses the same level integers as the 3D city (only the
 * Y-axis "height" is missing), the rule output and finding shape transfer
 * 1-to-1 — only Unity's (x, y, z) ground/height coordinate system has been
 * left behind.
 *
 * @param ruleId        rule identifier (R1, R2, R3, R4)
 * @param message       human-readable message including a classification reason
 * @param fromName      source element FQN
 * @param toName        target element FQN, may be null when only one side applies
 * @param fromLevel     source element level
 * @param toLevel       target element level (ignored when {@code toName} is null)
 * @param fromContainer package containing the source (for class endpoints)
 * @param toContainer   package containing the target
 */
public record InvariantFinding(
        String ruleId,
        String message,
        String fromName,
        String toName,
        int fromLevel,
        int toLevel,
        String fromContainer,
        String toContainer) {
}
