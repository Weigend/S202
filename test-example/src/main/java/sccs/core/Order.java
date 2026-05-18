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
package sccs.core;

import sccs.infra.PaymentRepository;

/**
 * Correct dep: Order -> PaymentRepository (core -> infra).
 * The heuristic wrongly cuts this edge (rank mismatch due to violation edges on PaymentRepository).
 */
public class Order {
    private final PaymentRepository repo;

    public Order(PaymentRepository repo) { this.repo = repo; }

    public String id() { return repo.nextId(); }
}
