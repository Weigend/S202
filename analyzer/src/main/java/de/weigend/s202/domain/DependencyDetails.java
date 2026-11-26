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
package de.weigend.s202.domain;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What carries one class dependency: the method calls behind it and the kinds
 * of relation it consists of. The one place that decides which recorded calls
 * belong to a dependency, so every view lists the same ones.
 *
 * @param kinds every kind of relation, calls included
 * @param calls the calls, most frequent first
 * @param typeUses the places that refer to the target without calling it, by kind;
 *              empty when the reader does not record them
 */
public record DependencyDetails(String from, String to, Set<EdgeKind> kinds, List<Call> calls,
                                List<TypeUse> typeUses) {

    /**
     * The calls one method makes to one method (name) of the target.
     *
     * @param member         the called method below the target class: {@code run}, or
     *                       {@code Inner.run} for a nested class, which counts for its outer one
     * @param descriptors    descriptors of the called overloads, sorted; empty when the
     *                       reader recorded none. {@code count} covers all of them
     * @param declaringClass the target, or the supertype it inherits the method from;
     *                       {@code null} when no analysed class is known to declare it
     */
    public record Call(String callerName, String callerDescriptor, String member, List<String> descriptors,
                       String declaringClass, int count) {

        /** Name of the called method without the nested class it may belong to. */
        public String methodName() {
            return member.substring(member.lastIndexOf('.') + 1);
        }

        public boolean isConstructor() {
            return "<init>".equals(methodName());
        }

        /** The called method's descriptor; {@code null} when unknown or when several overloads are called. */
        public String descriptor() {
            return descriptors.size() == 1 ? descriptors.get(0) : null;
        }
    }

    public DependencyDetails {
        kinds = Collections.unmodifiableSet(kinds.isEmpty() ? EnumSet.noneOf(EdgeKind.class) : EnumSet.copyOf(kinds));
        calls = List.copyOf(calls);
        typeUses = List.copyOf(typeUses);
    }

    /** The details of {@code from → to}; empty when the model does not know the dependency. */
    public static DependencyDetails of(DependencyModel model, String from, String to) {
        DependencyModel.ClassInfo source = model == null || from == null ? null : model.getClass(from);
        if (source == null || to == null) {
            return new DependencyDetails(from, to, Set.of(), List.of(), List.of());
        }
        List<Call> calls = new ArrayList<>();
        for (DependencyModel.MethodInfo caller : source.methods.values()) {
            for (Map.Entry<String, Integer> call : caller.methodCalls.entrySet()) {
                String member = memberOf(model, call.getKey(), to);
                if (member == null) {
                    continue;
                }
                List<String> descriptors = new ArrayList<>(
                        new TreeSet<>(caller.methodCallDescriptors.getOrDefault(call.getKey(), Set.of())));
                calls.add(new Call(caller.name, caller.descriptor, member, descriptors,
                        declaringClass(model, to, member, descriptors),
                        call.getValue() == null ? 0 : call.getValue()));
            }
        }
        calls.sort(Comparator.comparingInt(Call::count).reversed()
                .thenComparing(Call::callerName)
                .thenComparing(Call::callerDescriptor)
                .thenComparing(Call::member));
        List<TypeUse> typeUses = new ArrayList<>(source.typeUses.getOrDefault(to, Set.of()));
        typeUses.sort(Comparator.comparing(TypeUse::kind)
                .thenComparing(TypeUse::member)
                .thenComparing(TypeUse::descriptor, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(TypeUse::detail, Comparator.nullsFirst(Comparator.naturalOrder())));
        Set<EdgeKind> kinds = source.getKinds(to);
        if (kinds.isEmpty()) {
            kinds = source.externalDependencies.getOrDefault(to, EnumSet.noneOf(EdgeKind.class));
        }
        return new DependencyDetails(from, to, kinds, calls, typeUses);
    }

    /** Number of calls; for Java the weight of the dependency, unless there are none. */
    public int callCount() {
        return calls.stream().mapToInt(Call::count).sum();
    }

    /** The type uses without the {@code new} instructions, which the constructor calls stand for. */
    public List<TypeUse> references() {
        return typeUses.stream().filter(use -> use.kind() != TypeUse.Kind.NEW).toList();
    }

    /**
     * The kinds of relation neither calls nor type uses account for:
     * inheritance, imports — and whatever the reader recorded no places for.
     */
    public Set<EdgeKind> otherKinds() {
        EnumSet<EdgeKind> others = EnumSet.noneOf(EdgeKind.class);
        others.addAll(kinds);
        if (calls.stream().anyMatch(Call::isConstructor)) {
            others.remove(EdgeKind.INSTANTIATES);
        }
        if (calls.stream().anyMatch(call -> !call.isConstructor())) {
            others.remove(EdgeKind.CALLS);
        }
        if (!typeUses.isEmpty()) {
            others.remove(EdgeKind.USES);
        }
        return others;
    }

    /** The call that stands for the dependency: the most frequent one whose declaration is known. */
    public Optional<Call> mainCall() {
        return calls.stream().filter(call -> call.declaringClass() != null).findFirst()
                .or(() -> calls.stream().findFirst());
    }

    /**
     * The part of a recorded call ({@code owner.method}) below the class
     * {@code target}, or {@code null} when the call goes to another class. A
     * nested class counts for its outer class, as in the dependencies.
     */
    private static String memberOf(DependencyModel model, String call, String target) {
        if (call.length() <= target.length() + 1 || !call.startsWith(target)) {
            return null;
        }
        char separator = call.charAt(target.length());
        if (separator != '.' && separator != '$') {
            return null;
        }
        // A class of its own further down the name — in a package named like
        // the target — is the owner, not the target.
        if (separator == '.') {
            for (int dot = call.indexOf('.', target.length() + 1); dot > 0; dot = call.indexOf('.', dot + 1)) {
                if (model.getClass(call.substring(0, dot)) != null) {
                    return null;
                }
            }
        }
        return call.substring(target.length() + 1);
    }

    private static String declaringClass(DependencyModel model, String target, String member,
                                         List<String> descriptors) {
        if (descriptors.isEmpty()) {
            return MethodDeclarations.declaringClass(model, target, member, null);
        }
        for (String descriptor : descriptors) {
            String found = MethodDeclarations.declaringClass(model, target, member, descriptor);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
