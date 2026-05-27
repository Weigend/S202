# Refactoring with S202

## Goal

This document describes how S202 can be used to plan and evaluate refactorings. The focus is not on operating individual UI elements, but on the workflow from finding to decision.

## Planned Content

1. Architectural hypothesis as the starting point
2. Violations as refactoring hints
3. Evaluate back edges and cycles
4. Use CUTs as planned separations
5. Interpret what-if moves
6. Prioritize refactoring candidates
7. Perform before/after comparison
8. Limits of automatic derivation

## Guiding Questions

- Which violations are technically urgent?
- Which violations are accepted from a domain perspective?
- Which dependencies must be decoupled so that a target order becomes viable?
- Which CUTs actually dissolve an SCC?
