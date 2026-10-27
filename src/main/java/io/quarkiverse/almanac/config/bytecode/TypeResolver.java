package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.JarSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The processor's `ConfigAnnotationScanner#resolveType` over class files:
/// unwraps `Optional`, `List`/`Set`/arrays and `Map` values, and classifies the remaining type by loading its class
/// file as the module sees it (see [ClassPath#load(String, JarSource)]).
///
/// A type whose class file cannot be found anywhere is treated as a non-enum class leaf and reported once per module
/// through [Listener#unresolved].
final class TypeResolver {

    /// `Types.ALIASED_TYPES`, looked up by erased canonical name, so the `Class<?>` entry never matches.
    static final Map<String, String> ALIASES = Map.of(
            "java.util.OptionalLong", "long",
            "java.util.OptionalInt", "int",
            "java.util.OptionalDouble", "double",
            "java.lang.Class<?>", "class name",
            "java.net.InetSocketAddress", "host:port",
            "java.nio.file.Path", "path",
            "java.lang.String", "string");

    /// `Types.PRIMITIVE_WRAPPERS`: wrapper canonical name → primitive name.
    static final Map<String, String> PRIMITIVE_WRAPPERS = Map.of(
            "java.lang.Character", "char",
            "java.lang.Boolean", "boolean",
            "java.lang.Byte", "byte",
            "java.lang.Short", "short",
            "java.lang.Integer", "int",
            "java.lang.Long", "long",
            "java.lang.Float", "float",
            "java.lang.Double", "double");

    private static final String OPTIONAL = "java.util.Optional";
    private static final String MAP = "java.util.Map";
    private static final String LIST = "java.util.List";
    private static final String SET = "java.util.Set";
    private static final String DURATION = "java.time.Duration";
    private static final String RECORD = "java/lang/Record";

    /// Told about types whose class file cannot be found.
    @FunctionalInterface
    interface Listener {
        void unresolved(String qualifiedName);
    }

    private final ClassPath classPath;
    private final JarSource module;
    private final Listener listener;

    /// @param module   the jar being scanned; its own classes win over same-named classes of other jars
    /// @param listener told about every type that cannot be loaded
    TypeResolver(ClassPath classPath, JarSource module, Listener listener) {
        this.classPath = classPath;
        this.module = module;
        this.listener = listener;
    }

    /// Resolves a member type (a method's return type or a field's type).
    ResolvedType resolve(JavaType type) throws IOException {
        return switch (type) {
            case JavaType.Primitive primitive -> ResolvedType.ofPrimitive(primitive);
            case JavaType.Array array -> resolve(array.component()).asList(array);
            case JavaType.Declared declared -> resolveDeclared(declared);
            // wildcards and type variables never reach the processor at top level; be lenient anyway
            case JavaType.Wildcard wildcard -> resolve(wildcard.bound() != null ? wildcard.bound() : JavaType.OBJECT);
            case JavaType.Variable variable -> resolve(JavaType.OBJECT);
        };
    }

    private ResolvedType resolveDeclared(JavaType.Declared type) throws IOException {
        String erasedName = ClassFile.canonicalName(type.internalName());
        boolean optional = erasedName.startsWith(OPTIONAL);
        boolean map = erasedName.equals(MAP);
        boolean list = erasedName.equals(LIST) || erasedName.equals(SET);
        List<JavaType> arguments = type.arguments();
        if (arguments.size() == 1 && optional) {
            return resolve(arguments.getFirst()).asOptional();
        }
        if (arguments.size() == 1 && list) {
            return resolve(arguments.getFirst()).asList(type);
        }
        if (arguments.size() == 2 && map) {
            return resolve(arguments.get(1)).asMap(type);
        }
        Optional<ClassFile> loaded = classPath.load(type.internalName(), module);
        ClassFile classFile = loaded.orElse(null);
        String qualifiedName = classFile != null ? classFile.canonicalName() : erasedName;
        String simpleName = classFile != null ? classFile.simpleName() : ClassFile.simpleName(type.internalName());
        String simplifiedName = ALIASES.getOrDefault(qualifiedName,
                PRIMITIVE_WRAPPERS.getOrDefault(qualifiedName, simpleName));

        boolean isInterface = false;
        boolean isClass = false;
        boolean isEnum = false;
        boolean configGroup = false;
        if (classFile == null) {
            // not loadable: a non-enum class leaf
            listener.unresolved(qualifiedName);
            isClass = true;
        } else if (classFile.isEnum()) {
            isEnum = true;
        } else if (classFile.isAnnotation()) {
            // ElementKind.ANNOTATION_TYPE: neither interface nor class
        } else if (classFile.isInterface()) {
            isInterface = true;
            configGroup = classFile.annotations().containsKey(Annotations.CONFIG_GROUP);
        } else if (!RECORD.equals(classFile.node().superName)) {
            // ElementKind.RECORD is neither a class nor an interface for the processor
            isClass = true;
            configGroup = classFile.annotations().containsKey(Annotations.CONFIG_GROUP);
        }
        boolean duration = isClass && qualifiedName.equals(DURATION);
        ResolvedType resolved = new ResolvedType(type, qualifiedName, simplifiedName, false, false, false, false,
                isInterface, isClass, isEnum, duration, configGroup, classFile);
        // OptionalInt, OptionalLong and OptionalDouble have no type argument but are optional too
        return optional ? resolved.asOptional() : resolved;
    }

    /// `ElementUtil#simplifyGenericType`: simple names with type arguments, no aliasing or unboxing, no space after
    /// commas (`Map<String,List<String>>`); `unknown` for three or more type arguments.
    static String simplifyGenericType(JavaType type) {
        return switch (type) {
            case JavaType.Primitive primitive -> primitive.name();
            case JavaType.Array array -> simplifyGenericType(array.component()) + "[]";
            case JavaType.Variable variable -> variable.name();
            case JavaType.Wildcard wildcard -> wildcard.bound() == null ? "?" : simplifyGenericType(wildcard.bound());
            case JavaType.Declared declared -> {
                String simpleName = ClassFile.simpleName(declared.internalName());
                List<JavaType> arguments = declared.arguments();
                yield switch (arguments.size()) {
                    case 0 -> simpleName;
                    case 1 -> simpleName + "<" + simplifyGenericType(arguments.getFirst()) + ">";
                    case 2 -> simpleName + "<" + simplifyGenericType(arguments.get(0)) + ","
                            + simplifyGenericType(arguments.get(1)) + ">";
                    default -> "unknown";
                };
            }
        };
    }
}
