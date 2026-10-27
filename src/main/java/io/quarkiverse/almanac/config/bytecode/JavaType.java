package io.quarkiverse.almanac.config.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// A Java type as written in a member's generic signature (or, without one, its descriptor): what the annotation
/// processor sees as the member's `TypeMirror`.
sealed interface JavaType {

    /// `java/lang/Object`, used where a type variable or an unbounded wildcard stands.
    Declared OBJECT = new Declared("java/lang/Object", List.of());

    /// A primitive type, e.g. `int`.
    record Primitive(String name) implements JavaType {
    }

    /// An array type.
    record Array(JavaType component) implements JavaType {
    }

    /// A class or interface type.
    ///
    /// @param internalName JVM internal name, nested types joined with `$` (`a/b/Outer$Inner`)
    /// @param arguments    type arguments, empty for a raw or non-generic type
    record Declared(String internalName, List<JavaType> arguments) implements JavaType {
    }

    /// A type variable such as `T`.
    record Variable(String name) implements JavaType {
    }

    /// A wildcard type argument.
    ///
    /// @param kind  `*` (unbounded), `+` (extends) or `-` (super)
    /// @param bound the bound, null when unbounded
    record Wildcard(char kind, JavaType bound) implements JavaType {
    }

    /// The return type of a method from its signature or descriptor.
    static JavaType returnType(String methodSignature) {
        JavaType[] result = new JavaType[1];
        new SignatureReader(methodSignature).accept(new MethodSignature(type -> result[0] = type));
        if (result[0] == null) {
            throw new IllegalArgumentException("no return type in " + methodSignature);
        }
        return result[0];
    }

    /// The type of a field from its signature or descriptor.
    static JavaType fieldType(String fieldSignature) {
        JavaType[] result = new JavaType[1];
        new SignatureReader(fieldSignature).acceptType(new TypeBuilder(type -> result[0] = type));
        if (result[0] == null) {
            throw new IllegalArgumentException("no type in " + fieldSignature);
        }
        return result[0];
    }

    /// Collects the return type of a method signature and ignores everything else.
    final class MethodSignature extends SignatureVisitor {

        private final Consumer<JavaType> returnType;

        MethodSignature(Consumer<JavaType> returnType) {
            super(Opcodes.ASM9);
            this.returnType = returnType;
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return ignored();
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return ignored();
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return ignored();
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return new TypeBuilder(returnType);
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            return ignored();
        }

        private static SignatureVisitor ignored() {
            return new TypeBuilder(type -> {
            });
        }
    }

    /// Builds one [JavaType] from the visits of a type signature and hands it to a consumer.
    final class TypeBuilder extends SignatureVisitor {

        private final Consumer<JavaType> sink;
        private String internalName;
        private List<JavaType> arguments = new ArrayList<>();

        TypeBuilder(Consumer<JavaType> sink) {
            super(Opcodes.ASM9);
            this.sink = sink;
        }

        @Override
        public void visitBaseType(char descriptor) {
            sink.accept(new Primitive(primitiveName(descriptor)));
        }

        @Override
        public void visitTypeVariable(String name) {
            sink.accept(new Variable(name));
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return new TypeBuilder(component -> sink.accept(new Array(component)));
        }

        @Override
        public void visitClassType(String name) {
            internalName = name;
            arguments = new ArrayList<>();
        }

        @Override
        public void visitInnerClassType(String name) {
            // Outer<A>.Inner<B>: the type is Outer$Inner, its arguments are the inner ones
            internalName = internalName + "$" + name;
            arguments = new ArrayList<>();
        }

        @Override
        public void visitTypeArgument() {
            arguments.add(new Wildcard('*', null));
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            List<JavaType> target = arguments;
            return new TypeBuilder(argument -> target.add(wildcard == INSTANCEOF ? argument
                    : new Wildcard(wildcard, argument)));
        }

        @Override
        public void visitEnd() {
            sink.accept(new Declared(internalName, List.copyOf(arguments)));
        }

        private static String primitiveName(char descriptor) {
            return switch (descriptor) {
                case 'Z' -> "boolean";
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'S' -> "short";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'F' -> "float";
                case 'D' -> "double";
                case 'V' -> "void";
                default -> throw new IllegalArgumentException("unknown base type " + descriptor);
            };
        }
    }
}
