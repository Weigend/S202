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
package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.reader.DependencyModel;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves which concrete target method a class-level dependency edge most
 * likely represents: given an edge {@code from → to}, finds the method of
 * {@code to} that {@code from} calls most often. Stateless bytecode-model
 * lookup — used by the tangle view to turn an edge click into a method
 * selection.
 */
public final class TangleEdgeMethodResolver {

    public record TargetMethod(String className, String methodName, String descriptor, int callCount) {}

    private TangleEdgeMethodResolver() {
    }

    public static TargetMethod firstTargetMethodCalledByDependency(DependencyModel rawModel,
                                                                   String from,
                                                                   String to) {
        if (rawModel == null || from == null || to == null) {
            return null;
        }
        DependencyModel.ClassInfo sourceClass = rawModel.getClass(from);
        if (sourceClass == null) {
            return null;
        }
        Map<String, TargetMethod> candidates = new HashMap<>();
        for (DependencyModel.MethodInfo sourceMethod : sourceClass.methods.values()) {
            for (Map.Entry<String, Integer> call : sourceMethod.methodCalls.entrySet()) {
                String methodCall = call.getKey();
                if (!callOwnerMatchesTarget(methodCall, to)) {
                    continue;
                }
                String targetMethodName = methodCallName(methodCall);
                if (targetMethodName == null) {
                    continue;
                }
                Set<String> descriptors = sourceMethod.methodCallDescriptors.get(methodCall);
                if (descriptors == null || descriptors.isEmpty()) {
                    addTargetMethodCandidate(candidates, rawModel, to, targetMethodName, null, call.getValue());
                } else {
                    for (String descriptor : descriptors) {
                        addTargetMethodCandidate(candidates, rawModel, to, targetMethodName, descriptor, call.getValue());
                    }
                }
            }
        }
        return candidates.values().stream()
                .sorted(Comparator
                        .comparingInt(TargetMethod::callCount)
                        .reversed()
                        .thenComparing(TargetMethod::methodName)
                        .thenComparing(method -> method.descriptor() == null ? "" : method.descriptor()))
                .findFirst()
                .orElse(null);
    }

    private static void addTargetMethodCandidate(Map<String, TargetMethod> candidates,
                                                 DependencyModel rawModel,
                                                 String targetClass,
                                                 String methodName,
                                                 String descriptor,
                                                 Integer count) {
        String knownDescriptor = knownTargetDescriptor(rawModel, targetClass, methodName, descriptor);
        String key = targetClass + "#" + methodName + "#" + (knownDescriptor == null ? "" : knownDescriptor);
        int callCount = count == null ? 0 : count;
        TargetMethod existing = candidates.get(key);
        if (existing == null) {
            candidates.put(key, new TargetMethod(targetClass, methodName, knownDescriptor, callCount));
        } else {
            candidates.put(key, new TargetMethod(
                    existing.className(), existing.methodName(), existing.descriptor(),
                    existing.callCount() + callCount));
        }
    }

    private static String knownTargetDescriptor(DependencyModel rawModel,
                                                String targetClass,
                                                String methodName,
                                                String descriptor) {
        if (descriptor == null) {
            return null;
        }
        DependencyModel.ClassInfo targetInfo = rawModel.getClass(targetClass);
        if (targetInfo == null || targetInfo.getMethod(methodName, descriptor) == null) {
            return null;
        }
        return descriptor;
    }

    private static boolean callOwnerMatchesTarget(String methodCall, String targetClass) {
        String owner = methodCallOwner(methodCall);
        return owner != null
                && (targetClass.equals(owner) || targetClass.equals(outerClassName(owner)));
    }

    private static String methodCallOwner(String methodCall) {
        if (methodCall == null) {
            return null;
        }
        int dot = methodCall.lastIndexOf('.');
        return dot <= 0 ? null : methodCall.substring(0, dot);
    }

    private static String methodCallName(String methodCall) {
        if (methodCall == null) {
            return null;
        }
        int dot = methodCall.lastIndexOf('.');
        return dot < 0 || dot == methodCall.length() - 1 ? null : methodCall.substring(dot + 1);
    }

    private static String outerClassName(String className) {
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }
}
