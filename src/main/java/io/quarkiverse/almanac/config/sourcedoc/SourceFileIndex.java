package io.quarkiverse.almanac.config.sourcedoc;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The `@ConfigDoc*` annotations declared in one Java source file, by type and member.
///
/// - Types are keyed by their binary name relative to the package, e.g. `LogConfig$CategoryConfig`: the simple
///   names of the top-level type and its member types joined with `$`. Every top-level type of the file is
///   indexed. Local and anonymous classes are not (no config member lives in them).
/// - Members are fields (every variable of a declaration, and enum constants) and methods without parameters. A
///   record component counts as a field and, unless the record declares the accessor itself, as a method.
/// - Annotations are recognised by simple name (unless a single-type import binds it to another package) or by
///   fully qualified name in {@value #ANNOTATIONS_PACKAGE}: `ConfigDocDefault` and `ConfigDocMapKey`
///   (single-member or `value =` form), `ConfigDocIgnore` and `ConfigDocSection` (presence
///   only).
/// - Values are evaluated like javac folds constant expressions ([ConstantEvaluator]). A value that cannot be
///   evaluated becomes null and is listed in [#problems()].
///
/// The file is parsed at language level [#LANGUAGE_LEVEL] (the Java baseline of Quarkus 3) and, when that fails,
/// once more at [#NEWER_LANGUAGE_LEVEL]. Unicode escapes are translated before parsing, as javac does. Instances
/// are immutable and keep no syntax tree.
final class SourceFileIndex {

    static final String ANNOTATIONS_PACKAGE = "io.quarkus.runtime.annotations";
    static final String DOC_DEFAULT = "ConfigDocDefault";
    static final String DOC_MAP_KEY = "ConfigDocMapKey";
    static final String DOC_IGNORE = "ConfigDocIgnore";
    static final String DOC_SECTION = "ConfigDocSection";

    static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JAVA_17;
    static final LanguageLevel NEWER_LANGUAGE_LEVEL = LanguageLevel.JAVA_21;

    private static final Set<String> DOC_ANNOTATIONS = Set.of(DOC_DEFAULT, DOC_MAP_KEY, DOC_IGNORE, DOC_SECTION);
    private static final char UTF8_BOM = (char) 0xFEFF;

    /// A source file JavaParser rejects at both language levels.
    static final class UnparsableSourceException extends Exception {
        UnparsableSourceException(String message) {
            super(message);
        }
    }

    /// The indexed members of one type. Unannotated members map to [DocAnnotations#NONE].
    ///
    /// @param isInterface whether the type is an interface (or annotation type)
    /// @param fields      fields and enum constants by name
    /// @param methods     methods without parameters by name
    record TypeMembers(boolean isInterface, Map<String, DocAnnotations> fields, Map<String, DocAnnotations> methods) {

        /// The member a config reader means by this name: a method of an interface (`@ConfigMapping`), a field
        /// of a class (legacy `@ConfigRoot`/`@ConfigGroup`); the other kind when there is no such member.
        Optional<DocAnnotations> member(String name) {
            Map<String, DocAnnotations> first = isInterface ? methods : fields;
            Map<String, DocAnnotations> second = isInterface ? fields : methods;
            DocAnnotations found = first.get(name);
            return Optional.ofNullable(found != null ? found : second.get(name));
        }
    }

    /// A member carrying at least one doc annotation. Its `annotations` equal [DocAnnotations#NONE] when
    /// the only annotation has a value that could not be evaluated.
    ///
    /// @param type   binary name relative to the package, e.g. `LogConfig$CategoryConfig`
    /// @param method true for a method, false for a field
    record AnnotatedMember(String type, String name, boolean method, DocAnnotations annotations) {
    }

    private final String packageName;
    private final Map<String, TypeMembers> types;
    private final List<AnnotatedMember> annotatedMembers;
    private final List<String> problems;
    private final LanguageLevel languageLevel;

    private SourceFileIndex(String packageName, Map<String, TypeMembers> types, List<AnnotatedMember> annotatedMembers,
            List<String> problems, LanguageLevel languageLevel) {
        this.packageName = packageName;
        this.types = types;
        this.annotatedMembers = annotatedMembers;
        this.problems = problems;
        this.languageLevel = languageLevel;
    }

    /// Parses and indexes a source file.
    ///
    /// @param path    the file's path in its jar, used in messages
    /// @param content the file, UTF-8
    /// @throws UnparsableSourceException when the file does not parse at either language level
    static SourceFileIndex parse(String path, byte[] content) throws UnparsableSourceException {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == UTF8_BOM) {
            text = text.substring(1);
        }
        ParseResult<CompilationUnit> result = parse(path, text, LANGUAGE_LEVEL);
        LanguageLevel level = LANGUAGE_LEVEL;
        if (!isComplete(result)) {
            ParseResult<CompilationUnit> retry = parse(path, text, NEWER_LANGUAGE_LEVEL);
            if (!isComplete(retry)) {
                throw new UnparsableSourceException(path + ": " + firstProblem(result));
            }
            result = retry;
            level = NEWER_LANGUAGE_LEVEL;
        }
        CompilationUnit unit = result.getResult().orElseThrow();
        String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Map<String, TypeDeclaration<?>> declarations = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            collectTypes(type, type.getNameAsString(), declarations);
        }
        Indexer indexer = new Indexer(new ConstantEvaluator(packageName, declarations), foreignNames(unit));
        Map<String, TypeMembers> types = new LinkedHashMap<>();
        declarations.forEach((name, declaration) -> types.put(name, indexer.index(name, declaration)));
        List<AnnotatedMember> annotated = new ArrayList<>(indexer.annotated);
        annotated.sort(Comparator.comparing(AnnotatedMember::type).thenComparing(AnnotatedMember::method)
                .thenComparing(AnnotatedMember::name));
        return new SourceFileIndex(packageName, Map.copyOf(types), List.copyOf(annotated),
                List.copyOf(indexer.problems), level);
    }

    /// The package, dot-separated; empty for the default package.
    String packageName() {
        return packageName;
    }

    /// A type by binary name relative to the package, e.g. `LogConfig$CategoryConfig`.
    Optional<TypeMembers> type(String relativeBinaryName) {
        return Optional.ofNullable(types.get(relativeBinaryName));
    }

    /// Names of the indexed types (relative binary names), unordered.
    Set<String> typeNames() {
        return types.keySet();
    }

    /// Every member carrying a doc annotation, sorted by type, then fields before methods, then name.
    List<AnnotatedMember> annotatedMembers() {
        return annotatedMembers;
    }

    /// Annotation values that could not be evaluated, one message each (`Type#member: why`).
    List<String> problems() {
        return problems;
    }

    /// The language level that parsed the file.
    LanguageLevel languageLevel() {
        return languageLevel;
    }

    private static ParseResult<CompilationUnit> parse(String path, String text, LanguageLevel level)
            throws UnparsableSourceException {
        // a configuration per parse: JavaParser's pre-processors keep per-parse state
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(level)
                .setPreprocessUnicodeEscapes(true)
                .setAttributeComments(false)
                .setStoreTokens(false);
        try {
            return new JavaParser(configuration).parse(text);
        } catch (RuntimeException | StackOverflowError e) {
            // a parser failure on one odd file must not end the whole extraction
            throw new UnparsableSourceException(path + ": JavaParser failed at " + level + ": " + e);
        }
    }

    private static boolean isComplete(ParseResult<CompilationUnit> result) {
        return result.isSuccessful() && result.getResult().isPresent();
    }

    private static String firstProblem(ParseResult<CompilationUnit> result) {
        List<Problem> problems = result.getProblems();
        if (problems.isEmpty()) {
            return "no compilation unit";
        }
        String message = problems.get(0).getVerboseMessage().strip();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static void collectTypes(TypeDeclaration<?> type, String name, Map<String, TypeDeclaration<?>> out) {
        out.put(name, type);
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member.isTypeDeclaration()) {
                TypeDeclaration<?> nested = member.asTypeDeclaration();
                collectTypes(nested, name + "$" + nested.getNameAsString(), out);
            }
        }
    }

    /// Doc annotation names that a single-type import binds to another package in this file, so that a simple name
    /// `@ConfigDocDefault` there is not the Quarkus annotation.
    private static Set<String> foreignNames(CompilationUnit unit) {
        Set<String> foreign = new HashSet<>();
        for (ImportDeclaration declaration : unit.getImports()) {
            Name name = declaration.getName();
            if (!declaration.isStatic() && !declaration.isAsterisk() && DOC_ANNOTATIONS.contains(name.getIdentifier())
                    && !name.getQualifier().map(Name::asString).orElse("").equals(ANNOTATIONS_PACKAGE)) {
                foreign.add(name.getIdentifier());
            }
        }
        return foreign;
    }

    private static Optional<Expression> valueOf(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return Optional.of(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value"))
                    .map(MemberValuePair::getValue)
                    .findFirst();
        }
        return Optional.empty();
    }

    /// Reads the members of the types of one compilation unit, collecting what it cannot evaluate.
    private static final class Indexer {

        private final ConstantEvaluator constants;
        private final Set<String> foreignNames;
        private final List<AnnotatedMember> annotated = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        Indexer(ConstantEvaluator constants, Set<String> foreignNames) {
            this.constants = constants;
            this.foreignNames = foreignNames;
        }

        TypeMembers index(String typeName, TypeDeclaration<?> type) {
            Map<String, DocAnnotations> fields = new LinkedHashMap<>();
            Map<String, DocAnnotations> methods = new LinkedHashMap<>();
            if (type instanceof RecordDeclaration record) {
                for (Parameter component : record.getParameters()) {
                    String name = component.getNameAsString();
                    Optional<DocAnnotations> annotations = read(component.getAnnotations(), typeName, name);
                    put(fields, typeName, name, false, annotations);
                    put(methods, typeName, name, true, annotations);
                }
            }
            if (type instanceof EnumDeclaration enumeration) {
                for (EnumConstantDeclaration constant : enumeration.getEntries()) {
                    String name = constant.getNameAsString();
                    put(fields, typeName, name, false, read(constant.getAnnotations(), typeName, name));
                }
            }
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof FieldDeclaration field) {
                    for (VariableDeclarator variable : field.getVariables()) {
                        String name = variable.getNameAsString();
                        put(fields, typeName, name, false, read(field.getAnnotations(), typeName, name));
                    }
                } else if (member instanceof MethodDeclaration method && method.getParameters().isEmpty()) {
                    String name = method.getNameAsString();
                    put(methods, typeName, name, true, read(method.getAnnotations(), typeName, name));
                }
            }
            boolean isInterface = type.isAnnotationDeclaration()
                    || type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
            return new TypeMembers(isInterface, Map.copyOf(fields), Map.copyOf(methods));
        }

        private void put(Map<String, DocAnnotations> members, String typeName, String name, boolean method,
                Optional<DocAnnotations> annotations) {
            members.put(name, annotations.orElse(DocAnnotations.NONE));
            annotations.ifPresent(found -> annotated.add(new AnnotatedMember(typeName, name, method, found)));
        }

        /// The doc annotations among a declaration's annotations; empty when it has none.
        private Optional<DocAnnotations> read(NodeList<AnnotationExpr> annotations, String typeName, String member) {
            String defaultValue = null;
            String mapKey = null;
            boolean ignore = false;
            boolean section = false;
            boolean any = false;
            for (AnnotationExpr annotation : annotations) {
                String kind = docAnnotation(annotation.getName());
                if (kind == null) {
                    continue;
                }
                any = true;
                switch (kind) {
                    case DOC_DEFAULT -> defaultValue = stringValue(annotation, typeName, member);
                    case DOC_MAP_KEY -> mapKey = stringValue(annotation, typeName, member);
                    case DOC_IGNORE -> ignore = true;
                    default -> section = true;
                }
            }
            return any ? Optional.of(new DocAnnotations(defaultValue, mapKey, ignore, section)) : Optional.empty();
        }

        /// The simple name of a doc annotation written as `@X` (unless an import binds `X` elsewhere) or
        /// as `@io.quarkus.runtime.annotations.X`; null for any other annotation.
        private String docAnnotation(Name name) {
            if (!DOC_ANNOTATIONS.contains(name.getIdentifier())) {
                return null;
            }
            Optional<Name> qualifier = name.getQualifier();
            boolean quarkus = qualifier.isEmpty()
                    ? !foreignNames.contains(name.getIdentifier())
                    : qualifier.get().asString().equals(ANNOTATIONS_PACKAGE);
            return quarkus ? name.getIdentifier() : null;
        }

        private String stringValue(AnnotationExpr annotation, String typeName, String member) {
            Optional<Expression> value = valueOf(annotation);
            String where = typeName + "#" + member + ": @" + annotation.getName().getIdentifier();
            if (value.isEmpty()) {
                problems.add(where + " has no value");
                return null;
            }
            try {
                return constants.string(value.get(), typeName);
            } catch (ConstantEvaluator.NotConstantException e) {
                problems.add(where + "(" + value.get() + "): " + e.getMessage());
                return null;
            }
        }
    }
}
