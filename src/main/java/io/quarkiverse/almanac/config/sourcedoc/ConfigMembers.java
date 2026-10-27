package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/// The config members of a release, found the way the bytecode reader visits them, so that [SourceDocCheck] can look up what the reader will look up.
///
/// Starting from the roots listed in {@value #ROOTS_ENTRY} of every jar: an interface contributes its abstract,
/// non-synthetic methods without parameters (except `toString()` and void methods), a class its non-static,
/// non-synthetic fields; super-interfaces of interfaces and superclasses of classes are visited too. A member type is a
/// group, and visited, when it is (after unwrapping `Optional`, `List`, `Set`, the value of
/// `Map` and arrays) a non-JDK interface reached through a method, or a class annotated `@ConfigGroup`
/// reached through a field. Exclusions (`@ConfigItem(generateDocumentation = false)`, `@ConfigDocIgnore`)
/// are not applied: every member the reader may ask about is listed.
final class ConfigMembers {

    static final String ROOTS_ENTRY = "META-INF/quarkus-config-roots.list";

    private static final String CONFIG_GROUP = "Lio/quarkus/runtime/annotations/ConfigGroup;";
    private static final String OBJECT = "java/lang/Object";

    /// A member of a config type.
    ///
    /// @param owner  internal name of the declaring type
    /// @param method true for a method of an interface, false for a field of a class
    record Member(String owner, String name, boolean method) {

        /// `owner#name` for a field, `owner#name()` for a method.
        String key() {
            return owner + "#" + name + (method ? "()" : "");
        }
    }

    /// @param members        every config member, in visiting order
    /// @param types          number of types visited (roots, super-types and groups)
    /// @param missingClasses roots and super-types that no jar of the release contains, sorted
    record Result(List<Member> members, int types, List<String> missingClasses) {
    }

    /// How a type was reached, which decides whether it is visited.
    private enum Reach { ROOT, SUPER_TYPE, METHOD_TYPE, FIELD_TYPE }

    private record Visit(String type, Reach reach) {
    }

    private ConfigMembers() {
    }

    /// Collects the config members reachable from the roots of the given jars.
    static Result collect(VersionJars jars, List<JarSource> configJars) throws IOException {
        Deque<Visit> queue = new ArrayDeque<>();
        for (JarSource jar : configJars) {
            String roots = new String(jar.read(ROOTS_ENTRY).orElse(new byte[0]), StandardCharsets.UTF_8);
            for (String line : roots.split("\\R")) {
                if (!line.isBlank()) {
                    queue.add(new Visit(line.strip().replace('.', '/'), Reach.ROOT));
                }
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> rejected = new HashSet<>();
        Set<String> missing = new TreeSet<>();
        Set<Member> members = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            String type = visit.type();
            if (visited.contains(type) || type.startsWith("java/") || rejected.contains(visit.reach() + type)) {
                continue;
            }
            Optional<byte[]> bytes = jars.classBytes(type);
            if (bytes.isEmpty()) {
                if (visit.reach() == Reach.ROOT || visit.reach() == Reach.SUPER_TYPE) {
                    missing.add(type);
                }
                continue;
            }
            ClassNode node = ClassDocAnnotations.read(bytes.get());
            if (!isVisited(node, visit.reach())) {
                rejected.add(visit.reach() + type);
                continue;
            }
            visited.add(type);
            scan(node, members, queue);
        }
        return new Result(List.copyOf(members), visited.size(), List.copyOf(missing));
    }

    private static boolean isVisited(ClassNode node, Reach reach) {
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        boolean isAnnotation = (node.access & Opcodes.ACC_ANNOTATION) != 0;
        boolean isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
        return switch (reach) {
            case ROOT, SUPER_TYPE -> true;
            case METHOD_TYPE -> isInterface && !isAnnotation;
            case FIELD_TYPE -> !isInterface && !isEnum && hasAnnotation(node.visibleAnnotations, CONFIG_GROUP);
        };
    }

    private static void scan(ClassNode node, Set<Member> members, Deque<Visit> queue) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) {
            node.interfaces.forEach(type -> queue.add(new Visit(type, Reach.SUPER_TYPE)));
            for (MethodNode method : node.methods) {
                if (isConfigMethod(method)) {
                    members.add(new Member(node.name, method.name, true));
                    String signature = method.signature != null ? method.signature : method.desc;
                    String returnType = signature.substring(signature.lastIndexOf(')') + 1);
                    unwrap(returnType).ifPresent(type -> queue.add(new Visit(type, Reach.METHOD_TYPE)));
                }
            }
        } else {
            if (node.superName != null && !node.superName.equals(OBJECT)) {
                queue.add(new Visit(node.superName, Reach.SUPER_TYPE));
            }
            for (FieldNode field : node.fields) {
                if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) == 0) {
                    members.add(new Member(node.name, field.name, false));
                    unwrap(field.signature != null ? field.signature : field.desc)
                            .ifPresent(type -> queue.add(new Visit(type, Reach.FIELD_TYPE)));
                }
            }
        }
    }

    private static boolean isConfigMethod(MethodNode method) {
        int skipped = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_STATIC;
        return (method.access & Opcodes.ACC_ABSTRACT) != 0 && (method.access & skipped) == 0
                && method.desc.startsWith("()") && !method.desc.endsWith(")V")
                && !(method.name.equals("toString") && method.desc.equals("()Ljava/lang/String;"));
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals(descriptor));
    }

    /// The class a member's type designates for group detection: the type itself, or for `Optional<X>`,
    /// `List<X>`, `Set<X>`, `Map<K, X>` and `X[]` the unwrapped `X`.
    ///
    /// @param signature a field type signature or descriptor, e.g. `Ljava/util/Map<Ljava/lang/String;La/G;>;`
    /// @return the internal name (inner classes joined with `$`); empty for primitives and type variables
    static Optional<String> unwrap(String signature) {
        return unwrap(new SignatureParser(signature).type());
    }

    private static Optional<String> unwrap(TypeSignature type) {
        if (type.name() == null) {
            return Optional.empty();
        }
        List<TypeSignature> args = type.arguments();
        return switch (type.name()) {
            case "[" -> unwrap(args.get(0));
            case "java/util/Optional", "java/util/List", "java/util/Set" ->
                    args.size() == 1 ? unwrap(args.get(0)) : Optional.of(type.name());
            case "java/util/Map" -> args.size() == 2 ? unwrap(args.get(1)) : Optional.of(type.name());
            default -> Optional.of(type.name());
        };
    }

    /// A parsed reference type signature.
    ///
    /// @param name      the class's internal name, `"["` for an array (component as the only argument), null for
    ///                  primitives, type variables and unbounded wildcards
    /// @param arguments type arguments of the innermost class (wildcard bounds stand for themselves)
    private record TypeSignature(String name, List<TypeSignature> arguments) {
        static final TypeSignature NONE = new TypeSignature(null, List.of());
    }

    /// Recursive descent over the JVM type signature grammar (JVMS §4.7.9.1).
    private static final class SignatureParser {
        private final String text;
        private int position;

        SignatureParser(String text) {
            this.text = text;
        }

        TypeSignature type() {
            char c = text.charAt(position);
            if (c == '[') {
                position++;
                return new TypeSignature("[", List.of(type()));
            }
            if (c == 'L') {
                return classType();
            }
            if (c == 'T') {
                position = text.indexOf(';', position) + 1;
            } else {
                position++;
            }
            return TypeSignature.NONE;
        }

        private TypeSignature classType() {
            position++;
            StringBuilder name = new StringBuilder();
            List<TypeSignature> arguments = List.of();
            while (text.charAt(position) != ';') {
                char c = text.charAt(position);
                if (c == '<') {
                    arguments = typeArguments();
                } else if (c == '.') {
                    name.append('$');
                    arguments = List.of();
                    position++;
                } else {
                    name.append(c);
                    position++;
                }
            }
            position++;
            return new TypeSignature(name.toString(), arguments);
        }

        private List<TypeSignature> typeArguments() {
            position++;
            List<TypeSignature> arguments = new ArrayList<>();
            while (text.charAt(position) != '>') {
                char c = text.charAt(position);
                if (c == '*') {
                    position++;
                    arguments.add(TypeSignature.NONE);
                } else {
                    if (c == '+' || c == '-') {
                        position++;
                    }
                    arguments.add(type());
                }
            }
            position++;
            return arguments;
        }
    }
}
