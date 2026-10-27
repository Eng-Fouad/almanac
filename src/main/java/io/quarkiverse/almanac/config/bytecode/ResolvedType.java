package io.quarkiverse.almanac.config.bytecode;

/// The processor's `ResolvedType`: a member type with `Optional`,
/// `List`/`Set`/array and `Map` unwrapped into flags.
///
/// @param wrapper        the outermost type kept for lists and maps (the member type itself otherwise)
/// @param qualifiedName  canonical name of the unwrapped type (`int` for primitives)
/// @param simplifiedName the processor's `typeDescription` of the unwrapped type (`string`, `int`, `Duration`,
///                       `host:port`, an enum's simple name, …)
/// @param primitive      the unwrapped type is primitive
/// @param map            a `Map<K, V>`; the other fields describe `V`
/// @param list           a `List`, `Set` or array
/// @param optional       an `Optional<X>`, `OptionalInt`, `OptionalLong` or `OptionalDouble`
/// @param isInterface    the unwrapped type is an interface (not an annotation)
/// @param isClass        the unwrapped type is a class, or could not be loaded (treated as a class leaf)
/// @param isEnum         the unwrapped type is an enum
/// @param duration       the unwrapped type is `java.time.Duration`
/// @param configGroup    the unwrapped type carries `@ConfigGroup`
/// @param classFile      the unwrapped type's class file; null for primitives and types that could not be loaded
record ResolvedType(JavaType wrapper, String qualifiedName, String simplifiedName, boolean primitive, boolean map,
        boolean list, boolean optional, boolean isInterface, boolean isClass, boolean isEnum, boolean duration,
        boolean configGroup, ClassFile classFile) {

    static ResolvedType ofPrimitive(JavaType.Primitive type) {
        return new ResolvedType(type, type.name(), type.name(), true, false, false, false, false, false, false, false,
                false, null);
    }

    /// `ResolvedType.makeList`: a list of this type, keeping `wrapper` as the list type.
    ResolvedType asList(JavaType listType) {
        return new ResolvedType(listType, qualifiedName, simplifiedName, primitive, map, true, optional, isInterface,
                isClass, isEnum, duration, configGroup, classFile);
    }

    /// `ResolvedType.makeOptional`: the same type flagged optional (the wrapper is kept).
    ResolvedType asOptional() {
        return new ResolvedType(wrapper, qualifiedName, simplifiedName, primitive, map, list, true, isInterface,
                isClass, isEnum, duration, configGroup, classFile);
    }

    /// `ResolvedType.makeMap`: a map with values of this type (`list` is reset).
    ResolvedType asMap(JavaType mapType) {
        return new ResolvedType(mapType, qualifiedName, simplifiedName, primitive, true, false, optional, isInterface,
                isClass, isEnum, duration, configGroup, classFile);
    }
}
