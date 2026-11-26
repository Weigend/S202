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
package de.weigend.s202.reader.impl.java;

import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.TypeUse;
import de.weigend.s202.reader.TypeUse.Kind;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Records where one class refers to other types, see {@link TypeUse}, and adds
 * the {@link EdgeKind#USES} dependency that follows from it. Calls and
 * inheritance are the business of {@link InputAnalyzer}.
 */
final class TypeUseRecorder {

    private final DependencyModel.ClassInfo classInfo;
    private final String className;
    private final String memberPrefix;
    private final Predicate<String> excluded;
    private final Set<String> declaredFields = new HashSet<>();

    /**
     * @param classInfo the class the uses count for
     * @param className the visited class: {@code classInfo} itself or one nested in it
     * @param memberPrefix what the members of a nested class are named with, {@code Inner.}; else empty
     * @param excluded  the classes that stay out of the analysis
     */
    TypeUseRecorder(DependencyModel.ClassInfo classInfo, String className, String memberPrefix,
                    Predicate<String> excluded) {
        this.classInfo = classInfo;
        this.className = className;
        this.memberPrefix = memberPrefix;
        this.excluded = excluded;
    }

    /** The type arguments of the superclass and the interfaces, and the bounds of the type parameters. */
    void superTypeArguments(String signature) {
        addSignature(signature, Signature.CLASS, Kind.TYPE_PARAMETER, memberPrefix, null);
    }

    void field(String name, String descriptor, String signature) {
        declaredFields.add(name);
        String member = memberPrefix + name;
        add(Kind.FIELD, Type.getType(descriptor), member, null, null);
        addSignature(signature, Signature.FIELD, Kind.FIELD, member, null);
    }

    void method(String name, String descriptor, String signature, String[] exceptions) {
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            add(Kind.PARAMETER, argument, name, descriptor, null);
        }
        add(Kind.RETURN, Type.getReturnType(descriptor), name, descriptor, null);
        if (exceptions != null) {
            for (String exception : exceptions) {
                add(Kind.THROWS, Type.getObjectType(exception), name, descriptor, null);
            }
        }
        addSignature(signature, Signature.METHOD, Kind.TYPE_PARAMETER, name, descriptor);
    }

    /** {@code type} as ASM reports it: an internal name or an array descriptor. */
    void typeInstruction(DependencyModel.MethodInfo method, Kind kind, String type) {
        add(kind, Type.getObjectType(type), method.name, method.descriptor, null);
    }

    void classLiteral(DependencyModel.MethodInfo method, Type type) {
        add(Kind.CLASS_LITERAL, type, method.name, method.descriptor, null);
    }

    void fieldAccess(DependencyModel.MethodInfo method, String owner, String field, String fieldDescriptor) {
        Type ownerType = Type.getObjectType(owner);
        if (ownerType.getClassName().equals(className) && declaredFields.contains(field)) {
            return; // the declaration of the field says it already
        }
        String detail = fieldName(ownerType, field);
        add(Kind.FIELD_ACCESS, ownerType, method.name, method.descriptor, detail);
        add(Kind.FIELD_ACCESS, Type.getType(fieldDescriptor), method.name, method.descriptor, detail);
    }

    /** Records an annotation and returns the visitor for what its values refer to. */
    AnnotationVisitor annotation(String member, String descriptor, String annotation) {
        add(Kind.ANNOTATION, Type.getType(annotation), member, descriptor, null);
        return annotationValues(member, descriptor);
    }

    AnnotationVisitor annotationValues(String member, String descriptor) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof Type type) {
                    add(Kind.CLASS_LITERAL, type, member, descriptor, null);
                }
            }

            @Override
            public void visitEnum(String name, String enumDescriptor, String value) {
                Type type = Type.getType(enumDescriptor);
                add(Kind.FIELD_ACCESS, type, member, descriptor, fieldName(type, value));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String nested) {
                return annotation(member, descriptor, nested);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }
        };
    }

    /** {@code Owner.field} */
    private static String fieldName(Type owner, String field) {
        String name = owner.getClassName();
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.') + "." + field;
    }

    private void add(Kind kind, Type type, String member, String descriptor, String detail) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() != Type.OBJECT) {
            return;
        }
        // Nested classes count for their outer class, as in the dependencies of calls.
        String target = element.getClassName();
        int dollar = target.indexOf('$');
        if (dollar > 0) {
            target = target.substring(0, dollar);
        }
        if (target.equals(classInfo.fullName)) {
            return;
        }
        if (excluded.test(target)) {
            classInfo.addExternalDependency(target, EdgeKind.USES);
        } else {
            classInfo.addTypeUse(target, new TypeUse(kind, member, descriptor, detail));
            classInfo.addDependency(target, EdgeKind.USES);
        }
    }

    private enum Signature { CLASS, FIELD, METHOD }

    /** The types only the generic signature names. */
    private void addSignature(String signature, Signature of, Kind initialKind, String member, String descriptor) {
        if (signature == null) {
            return;
        }
        SignatureVisitor visitor = new SignatureVisitor(Opcodes.ASM9) {
            private Kind kind = initialKind;
            /** The supertypes themselves are no type uses, only their arguments are. */
            private boolean superType;

            @Override
            public SignatureVisitor visitSuperclass() {
                return visitInterface();
            }

            @Override
            public SignatureVisitor visitInterface() {
                superType = true;
                return this;
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                if (superType) {
                    superType = false;
                    kind = Kind.TYPE_ARGUMENT;
                }
                return this;
            }

            @Override
            public SignatureVisitor visitParameterType() {
                kind = Kind.PARAMETER;
                return this;
            }

            @Override
            public SignatureVisitor visitReturnType() {
                kind = Kind.RETURN;
                return this;
            }

            @Override
            public SignatureVisitor visitExceptionType() {
                kind = Kind.THROWS;
                return this;
            }

            @Override
            public void visitClassType(String name) {
                if (!superType) {
                    add(kind, Type.getObjectType(name), member, descriptor, null);
                }
            }
        };
        try {
            SignatureReader reader = new SignatureReader(signature);
            if (of == Signature.FIELD) {
                reader.acceptType(visitor);
            } else {
                reader.accept(visitor);
            }
        } catch (RuntimeException malformedSignature) {
            // Compilers may write what they like here; the descriptor has been recorded.
        }
    }
}
