package io.quarkiverse.almanac.config.sourcedoc;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The `@ConfigDoc*` annotations as class files carry them (RUNTIME retention since Quarkus 3.14): the
/// reference that [SourceDocCheck] compares the source reading with. Only declaration annotations count
/// (`RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations`), never type annotations.
final class ClassDocAnnotations {

    private static final String PREFIX = "Lio/quarkus/runtime/annotations/";
    private static final String DEFAULT = PREFIX + SourceFileIndex.DOC_DEFAULT + ";";
    private static final String MAP_KEY = PREFIX + SourceFileIndex.DOC_MAP_KEY + ";";
    private static final String IGNORE = PREFIX + SourceFileIndex.DOC_IGNORE + ";";
    private static final String SECTION = PREFIX + SourceFileIndex.DOC_SECTION + ";";
    private static final int READ_FLAGS = ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;

    /// A field or method of a class file with at least one doc annotation.
    ///
    /// @param owner      internal name of the class
    /// @param method     true for a method, false for a field
    /// @param descriptor the member's descriptor, e.g. `()Ljava/util/Map;`
    record Member(String owner, String name, boolean method, String descriptor, DocAnnotations annotations) {

        /// Whether a config reader could ask for this member: a field, or a method without parameters.
        boolean isConfigShaped() {
            return !method || descriptor.startsWith("()");
        }
    }

    private ClassDocAnnotations() {
    }

    /// Parses a class file, skipping code.
    static ClassNode read(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, READ_FLAGS);
        return node;
    }

    /// Whether a class file is an interface (annotation types included), whose config members are methods.
    static boolean isInterface(ClassNode node) {
        return (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    /// The `SourceFile` attribute of a class file: the name, without directories, of the file javac compiled it
    /// from, e.g. `TransactionManagerConfiguration.java`; empty when the attribute is absent or the class file
    /// cannot be read (the attribute only serves a best-effort fallback).
    static Optional<String> sourceFile(byte[] classFile) {
        String[] name = new String[1];
        try {
            // SKIP_DEBUG would skip the SourceFile attribute
            new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitSource(String source, String debug) {
                    name[0] = source;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            // ASM signals a malformed class file with unchecked exceptions
            return Optional.empty();
        }
        return Optional.ofNullable(name[0]);
    }

    /// The fields and methods of a class that carry a doc annotation, fields first, in class-file order.
    static List<Member> annotatedMembers(ClassNode node) {
        List<Member> result = new ArrayList<>();
        for (FieldNode field : node.fields) {
            DocAnnotations annotations = of(field.visibleAnnotations, field.invisibleAnnotations);
            if (!annotations.equals(DocAnnotations.NONE)) {
                result.add(new Member(node.name, field.name, false, field.desc, annotations));
            }
        }
        for (MethodNode method : node.methods) {
            DocAnnotations annotations = of(method.visibleAnnotations, method.invisibleAnnotations);
            if (!annotations.equals(DocAnnotations.NONE)) {
                result.add(new Member(node.name, method.name, true, method.desc, annotations));
            }
        }
        return result;
    }

    /// The doc annotations of a field, or of a method without parameters, of a class.
    ///
    /// @return empty when the class has no such member
    static Optional<DocAnnotations> member(ClassNode node, String name, boolean method) {
        if (method) {
            return node.methods.stream()
                    .filter(m -> m.name.equals(name) && m.desc.startsWith("()"))
                    .findFirst()
                    .map(m -> of(m.visibleAnnotations, m.invisibleAnnotations));
        }
        return node.fields.stream()
                .filter(f -> f.name.equals(name))
                .findFirst()
                .map(f -> of(f.visibleAnnotations, f.invisibleAnnotations));
    }

    private static DocAnnotations of(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        String defaultValue = null;
        String mapKey = null;
        boolean ignore = false;
        boolean section = false;
        boolean any = false;
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) {
            all.addAll(visible);
        }
        if (invisible != null) {
            all.addAll(invisible);
        }
        for (AnnotationNode annotation : all) {
            if (annotation.desc.equals(DEFAULT)) {
                defaultValue = stringValue(annotation);
            } else if (annotation.desc.equals(MAP_KEY)) {
                mapKey = stringValue(annotation);
            } else if (annotation.desc.equals(IGNORE)) {
                ignore = true;
            } else if (annotation.desc.equals(SECTION)) {
                section = true;
            } else {
                continue;
            }
            any = true;
        }
        return any ? new DocAnnotations(defaultValue, mapKey, ignore, section) : DocAnnotations.NONE;
    }

    /// The `value` element; [AnnotationNode#values] alternates names and values.
    private static String stringValue(AnnotationNode annotation) {
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }
}
