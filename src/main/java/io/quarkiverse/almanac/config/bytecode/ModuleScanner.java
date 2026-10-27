package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.sourcedoc.DocAnnotations;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/// One run of the Quarkus 3.25 config documentation processor over one module (jar), on class files
///: reads the roots listed by the module, scans their members
/// (super-types first), registers config groups and enums once per module, then resolves paths and leaf attributes.
///
/// Interfaces are `@ConfigMapping` roots and groups whose abstract methods are properties; classes are legacy
/// `@ConfigRoot` roots and `@ConfigGroup` groups whose fields are properties. This is how the processor treats its
/// "mixed modules" (`quarkus-core`, `quarkus-vertx-http`), applied to every module so that releases of every era are
/// read with the same rules.
///
/// Properties come out in the order of the shipped model: roots in the order of the processor's `ConfigCollector` (a
/// `HashMap` keyed by canonical name, filled in source path order), and the items of group members with
/// `@ConfigDocSection` gathered per section path. So the model reader and this reader list properties alike and keep
/// the same one when a module produces a key twice. Not thread-safe: one instance scans one module.
final class ModuleScanner {

    /// An enum constant with its `@ConfigDocEnumValue` value, null when it has none.
    record EnumConstant(String name, String explicitValue) {
    }

    /// What a module yields.
    ///
    /// @param roots      canonical names of the roots read, sorted
    /// @param properties the resolved properties, in model order
    /// @param warnings   problems found while reading the module
    record Result(JarSource jar, List<String> roots, List<LeafProperty> properties, List<String> warnings) {
    }

    /// A config root or group being filled: properties keyed by member name in first-insertion order.
    private static final class Element {

        private final boolean mapping;
        private final Map<String, DiscoveredProperty> properties = new LinkedHashMap<>();

        Element(boolean mapping) {
            this.mapping = mapping;
        }
    }

    private record Root(String canonicalName, String prefix, String phase, Element element) {
    }

    /// An item collection of the model (a root or a `ConfigSection`): leaves and nested sections in model order.
    /// Sections only change the order in which the model lists a root's properties.
    private static final class Items {

        private final List<Object> entries = new ArrayList<>();

        void flattenInto(List<LeafProperty> out) {
            for (Object entry : entries) {
                if (entry instanceof Items section) {
                    section.flattenInto(out);
                } else {
                    out.add((LeafProperty) entry);
                }
            }
        }
    }

    /// The resolution context of `ConfigResolver#resolveProperty`.
    ///
    /// @param items where the next items go: the root's collection or a section's
    private record Context(String path, List<String> additionalPaths, boolean withinMap, String deprecated,
            Items items) {
    }

    private static final String OBJECT = "java/lang/Object";
    private static final String BUILD_TIME = "BUILD_TIME";
    private static final String JDK_PREFIX = "java.";

    private final JarSource jar;
    private final ClassPath classPath;
    private final ModelFormat format;
    private final MemberDocs docs;
    private final TypeResolver types;
    private final Map<String, Element> groups = new HashMap<>();
    private final Map<String, List<EnumConstant>> enums = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private String currentMember;

    /// @param jar        the module
    /// @param classPath  the release-wide class path
    /// @param format     how the release's own processor writes enum values and lists of groups
    /// @param docs       `@ConfigDoc*` annotations missing from pre-3.14 class files
    /// @param unresolved collects the types that cannot be loaded, release-wide
    ModuleScanner(JarSource jar, ClassPath classPath, ModelFormat format, MemberDocs docs,
            UnresolvedTypes unresolved) {
        this.jar = jar;
        this.classPath = classPath;
        this.format = format;
        this.docs = docs;
        this.types = new TypeResolver(classPath, jar, name -> unresolved.add(name, currentMember));
    }

    /// Reads the roots of the module.
    ///
    /// @param rootBinaryNames the lines of the module's `META-INF/quarkus-config-roots.list`
    Result scan(List<String> rootBinaryNames) throws IOException {
        // ConfigCollector#configRoots is a HashMap keyed by canonical name, discovered in source path order
        Map<String, Root> roots = new HashMap<>();
        for (String binaryName : new TreeSet<>(rootBinaryNames)) {
            try {
                Optional<Root> root = scanRoot(binaryName);
                if (root.isPresent() && roots.putIfAbsent(root.get().canonicalName(), root.get()) != null) {
                    warnings.add(jar.artifactId() + ": config root " + binaryName + " is listed twice");
                }
            } catch (RuntimeException e) {
                warnings.add(jar.artifactId() + ": cannot read config root " + binaryName + ": " + e);
            }
        }
        List<LeafProperty> properties = new ArrayList<>();
        for (Root root : roots.values()) {
            Items items = new Items();
            // ConfigResolver#resolveModel: existingRootConfigSections, per root
            Map<String, Items> sections = new HashMap<>();
            Context context = new Context(root.prefix(), List.of(), false, null, items);
            for (DiscoveredProperty property : root.element().properties.values()) {
                resolve(root, context, property, sections, new ArrayDeque<>());
            }
            items.flattenInto(properties);
        }
        return new Result(jar, List.copyOf(new TreeSet<>(roots.keySet())), properties, List.copyOf(warnings));
    }

    /// `ConfigMappingListener#onConfigRoot` / `LegacyConfigRootListener#onConfigRoot`, then the scan.
    private Optional<Root> scanRoot(String binaryName) throws IOException {
        Optional<ClassFile> loaded = classPath.load(binaryName.replace('.', '/'), jar);
        if (loaded.isEmpty()) {
            warnings.add(jar.artifactId() + ": config root " + binaryName + " not found");
            return Optional.empty();
        }
        ClassFile type = loaded.get();
        AnnotationNode configRoot = type.annotations().get(Annotations.CONFIG_ROOT);
        String phase = orElse(Annotations.enumConstant(configRoot, "phase"), BUILD_TIME);
        String prefix;
        if (type.isInterface()) {
            AnnotationNode configMapping = type.annotations().get(Annotations.CONFIG_MAPPING);
            if (configMapping == null) {
                warnings.add(jar.artifactId() + ": config root " + binaryName + " is an interface without "
                        + "@ConfigMapping; using prefix " + ConfigNames.DEFAULT_PREFIX);
            }
            String mappingPrefix = orElse(Annotations.string(configMapping, "prefix"), ConfigNames.DEFAULT_PREFIX);
            prefix = ConfigNames.rootPrefix(mappingPrefix, "", type.simpleName(), phase);
        } else {
            String name = orElse(Annotations.string(configRoot, "name"), ConfigNames.HYPHENATED_ELEMENT_NAME);
            String rootPrefix = orElse(Annotations.string(configRoot, "prefix"), ConfigNames.DEFAULT_PREFIX);
            prefix = ConfigNames.rootPrefix(rootPrefix, name, type.simpleName(), phase);
        }
        Element element = new Element(type.isInterface());
        scan(element, type);
        return Optional.of(new Root(type.canonicalName(), prefix, phase, element));
    }

    /// `ConfigAnnotationScanner#scanElement`: super-types first, then the declared members.
    private void scan(Element element, ClassFile type) throws IOException {
        if (type.isInterface()) {
            for (String superInterface : type.node().interfaces) {
                Optional<ClassFile> loaded = loadSuperType(superInterface, type);
                if (loaded.isPresent()) {
                    scan(element, loaded.get());
                }
            }
        } else if (type.node().superName != null && !OBJECT.equals(type.node().superName)) {
            Optional<ClassFile> loaded = loadSuperType(type.node().superName, type);
            if (loaded.isPresent()) {
                scan(element, loaded.get());
            }
        }
        for (MethodNode method : type.node().methods) {
            scanMethod(element, type, method);
        }
        for (FieldNode field : type.node().fields) {
            scanField(element, type, field);
        }
    }

    private Optional<ClassFile> loadSuperType(String internalName, ClassFile subType) throws IOException {
        Optional<ClassFile> loaded = classPath.load(internalName, jar);
        if (loaded.isEmpty()) {
            warnings.add(jar.artifactId() + ": super-type " + ClassFile.canonicalName(internalName) + " of "
                    + subType.canonicalName() + " not found; its members are missing");
        }
        return loaded;
    }

    /// One method: properties for mapping elements, group and enum registration for every element.
    private void scanMethod(Element element, ClassFile owner, MethodNode method) throws IOException {
        // ConfigAnnotationScanner#isMethodIgnored: default, static, private, void and toString() methods
        if ((method.access & Opcodes.ACC_ABSTRACT) == 0 || method.desc.endsWith(")V")
                || (method.name.equals("toString") && method.desc.startsWith("()"))) {
            return;
        }
        Map<String, AnnotationNode> annotations =
                Annotations.declared(method.visibleAnnotations, method.invisibleAnnotations);
        DocAnnotations doc = docs.get(owner.internalName(), method.name);
        if (annotations.containsKey(Annotations.CONFIG_DOC_IGNORE) || doc.ignore()) {
            return;
        }
        String signature = method.signature != null ? method.signature : method.desc;
        ResolvedType type = resolveMember(owner, method.name, () -> JavaType.returnType(signature));
        if (type == null) {
            return;
        }
        register(type, true);
        // ConfigMappingListener#onEnclosedMethod (mixed-module rule: only for mapping elements)
        if (element.mapping) {
            element.properties.put(method.name,
                    DiscoveredProperty.ofMethod(owner.canonicalName(), method.name, annotations, doc, type));
        }
    }

    /// One field: properties for legacy elements, group and enum registration for every element.
    private void scanField(Element element, ClassFile owner, FieldNode field) throws IOException {
        // ConfigAnnotationScanner#isFieldIgnored; synthetic fields (this$0) are invisible to the processor. Final
        // fields are no config items either: the legacy config runtime skips them (BuildTimeConfigurationReader
        // #processClassFields), and no shipped model has one. The only real case is the private final `configs`
        // cache of RestClientsConfig (3.0 to 3.13), which would otherwise add 30 to 35 deprecated properties that
        // never existed
        if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL)) != 0) {
            return;
        }
        Map<String, AnnotationNode> annotations =
                Annotations.declared(field.visibleAnnotations, field.invisibleAnnotations);
        DocAnnotations doc = docs.get(owner.internalName(), field.name);
        AnnotationNode configItem = annotations.get(Annotations.CONFIG_ITEM);
        if (configItem != null) {
            // nothing else is checked when @ConfigItem is present
            if (Boolean.FALSE.equals(Annotations.value(configItem, "generateDocumentation"))) {
                return;
            }
        } else if (annotations.containsKey(Annotations.CONFIG_DOC_IGNORE) || doc.ignore()) {
            return;
        }
        String signature = field.signature != null ? field.signature : field.desc;
        ResolvedType type = resolveMember(owner, field.name, () -> JavaType.fieldType(signature));
        if (type == null) {
            return;
        }
        register(type, false);
        // LegacyConfigRootListener#onEnclosedField (mixed-module rule: only for legacy elements)
        if (!element.mapping) {
            element.properties.put(field.name,
                    DiscoveredProperty.ofField(owner.canonicalName(), field.name, annotations, doc, type));
        }
    }

    /// Parses a member's generic type, which can fail on malformed signatures.
    @FunctionalInterface
    private interface TypeParser {
        JavaType parse();
    }

    private ResolvedType resolveMember(ClassFile owner, String member, TypeParser parser) throws IOException {
        currentMember = owner.canonicalName() + "#" + member;
        JavaType javaType;
        try {
            javaType = parser.parse();
        } catch (RuntimeException e) {
            warnings.add(jar.artifactId() + ": unreadable signature of " + currentMember + ": " + e.getMessage());
            return null;
        }
        return types.resolve(javaType);
    }

    /// Registers an enum, or a config group (scanned once per module, like `isConfigGroupAlreadyHandled`).
    private void register(ResolvedType type, boolean viaMethod) throws IOException {
        if (type.isEnum()) {
            enums.computeIfAbsent(type.qualifiedName(), name -> constants(type.classFile()));
            return;
        }
        boolean group = viaMethod
                ? type.isInterface() && !type.qualifiedName().startsWith(JDK_PREFIX)
                : type.isClass() && type.configGroup();
        if (group && !groups.containsKey(type.qualifiedName())) {
            Element element = new Element(type.classFile().isInterface());
            groups.put(type.qualifiedName(), element);
            scan(element, type.classFile());
        }
    }

    /// `AbstractConfigListener#onResolvedEnum`: constants in declaration order.
    private static List<EnumConstant> constants(ClassFile enumType) {
        List<EnumConstant> constants = new ArrayList<>();
        for (FieldNode field : enumType.node().fields) {
            if ((field.access & Opcodes.ACC_ENUM) != 0) {
                AnnotationNode value = Annotations.declared(field.visibleAnnotations, field.invisibleAnnotations)
                        .get(Annotations.CONFIG_DOC_ENUM_VALUE);
                constants.add(new EnumConstant(field.name, Annotations.string(value, "value")));
            }
        }
        return List.copyOf(constants);
    }

    /// `ConfigResolver#resolveProperty`: groups expand in place, everything else is a leaf. A group member
    /// with `@ConfigDocSection` puts its items into the root's section of the same path, created at the current
    /// position the first time, which is what orders the model's properties.
    private void resolve(Root root, Context context, DiscoveredProperty property, Map<String, Items> sections,
            Deque<String> expanding) {
        String path = ConfigNames.appendPath(context.path(), property.name());
        List<String> additionalPaths = context.additionalPaths().stream()
                .map(additional -> ConfigNames.appendPath(additional, property.name()))
                .toList();
        String deprecated = property.deprecated() != null ? property.deprecated() : context.deprecated();
        ResolvedType type = property.type();
        Element group = groups.get(type.qualifiedName());
        if (group == null) {
            context.items().entries.add(leaf(root, context, property, path, additionalPaths, deprecated));
            return;
        }
        String groupPath = path;
        if (type.map()) {
            String key = ConfigNames.mapKey(property.mapKey());
            if (property.unnamedMapKey()) {
                // the primary path stays unkeyed: [path+key, a1, a1+key, a2, a2+key, …]
                List<String> keyed = new ArrayList<>();
                keyed.add(path + key);
                for (String additional : additionalPaths) {
                    keyed.add(additional);
                    keyed.add(additional + key);
                }
                additionalPaths = keyed;
            } else {
                groupPath = path + key;
                additionalPaths = additionalPaths.stream().map(additional -> additional + key).toList();
            }
        } else if (type.list() && format.listIndex()) {
            // later processors index lists of groups, on the primary path only
            groupPath = path + ModelFormat.LIST_INDEX_SEGMENT;
        }
        if (expanding.contains(type.qualifiedName())) {
            warnings.add(jar.artifactId() + ": config group " + type.qualifiedName() + " contains itself at "
                    + path + "; not expanded again");
            return;
        }
        Items items = context.items();
        if (property.section()) {
            items = sections.get(path);
            if (items == null) {
                items = new Items();
                context.items().entries.add(items);
                sections.put(path, items);
            }
        }
        expanding.push(type.qualifiedName());
        Context groupContext = new Context(groupPath, List.copyOf(additionalPaths),
                context.withinMap() || type.map(), deprecated, items);
        for (DiscoveredProperty member : group.properties.values()) {
            resolve(root, groupContext, member, sections, expanding);
        }
        expanding.pop();
    }

    /// The leaf branch of `ConfigResolver#resolveProperty`.
    private LeafProperty leaf(Root root, Context context, DiscoveredProperty property, String path,
            List<String> additionalPaths, String deprecated) {
        ResolvedType type = property.type();
        // per historical rules, enum values of converted properties are not hyphenated
        boolean hyphenate = property.enforceHyphenateEnumValue() || !property.converted();
        String defaultValue = defaultValue(property, type, hyphenate);
        String leafPath = path;
        List<String> leafAdditionalPaths = additionalPaths;
        boolean optional = type.optional();
        String typeDescription = type.simplifiedName();
        if (type.map()) {
            // a leaf map is always optional; its key is the last path segment
            optional = true;
            typeDescription = TypeResolver.simplifyGenericType(type.wrapper());
            String key = ConfigNames.mapKey(property.mapKey());
            leafPath = path + key;
            leafAdditionalPaths = additionalPaths.stream().map(additional -> additional + key).toList();
        }
        List<String> enumValues = null;
        if (type.isEnum()) {
            List<EnumConstant> constants = new ArrayList<>(enums.getOrDefault(type.qualifiedName(), List.of()));
            if (format.sortedEnumValues()) {
                constants.sort(Comparator.comparing(EnumConstant::name));
            }
            enumValues = constants.stream()
                    .map(constant -> constant.explicitValue() != null ? constant.explicitValue()
                            : hyphenate ? ConfigNames.hyphenateEnumValue(constant.name()) : constant.name())
                    .toList();
        }
        return new LeafProperty(leafPath, List.copyOf(leafAdditionalPaths), typeDescription, type.map(), type.list(),
                optional, property.mapKey(), property.unnamedMapKey(), context.withinMap(), property.converted(),
                type.isEnum(), enumValues, defaultValue, deprecated, root.phase(), property.sourceType(),
                property.sourceElementName());
    }

    /// `ConfigResolver#getDefaultValue`: a non-blank doc default verbatim, else the default with enum
    /// constants hyphenated (each element of a comma-separated list default).
    static String defaultValue(DiscoveredProperty property, ResolvedType type, boolean hyphenate) {
        String forDoc = property.defaultValueForDoc();
        if (forDoc != null && !forDoc.isBlank()) {
            return forDoc;
        }
        String value = property.defaultValue();
        if (value == null || !type.isEnum() || !hyphenate) {
            return value;
        }
        if (type.list()) {
            List<String> values = new ArrayList<>();
            for (String element : value.split(",")) {
                values.add(ConfigNames.hyphenateEnumValue(element.trim()));
            }
            return String.join(",", values);
        }
        return ConfigNames.hyphenateEnumValue(value.trim());
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
