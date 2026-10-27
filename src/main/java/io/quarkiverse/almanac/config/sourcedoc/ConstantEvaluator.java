package io.quarkiverse.almanac.config.sourcedoc;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Evaluates annotation values of one compilation unit the way javac folds compile-time constants.
///
/// Supported: string literals (escape sequences decoded), text blocks (line terminators normalised, incidental
/// white space stripped, then escapes decoded, JLS §3.10.6), char, int, long, float, double and boolean literals,
/// parentheses, unary `+`/`-` on numbers, binary `+` (string concatenation, or numeric addition with
/// binary numeric promotion, left to right), casts to `String`, and references to constants declared in the same
/// compilation unit: `static final` fields with an initializer, or fields of an interface or annotation type. A
/// simple name is looked up in the annotated member's type, then in its enclosing types; a qualified name
/// (`Nested.NAME`, `Outer.Nested.NAME`, `com.acme.Outer.NAME`) must name a type of this compilation
/// unit. Everything else (constants of other files, static imports, inherited constants, other operators) is reported
/// as not constant.
///
/// Unicode escapes are expected to be translated already (the parser does it, as javac does).
final class ConstantEvaluator {

    /// A value that is not a compile-time constant of this compilation unit; the message says why.
    static final class NotConstantException extends Exception {
        NotConstantException(String message) {
            super(message);
        }
    }

    private final String packageName;
    private final Map<String, TypeDeclaration<?>> types;
    /// Initializers of the constant fields, keyed `Type#name` (relative binary type names).
    private final Map<String, Expression> constants = new HashMap<>();
    /// Every other field, keyed `Type#name`, to tell "not constant" from "not declared here".
    private final Set<String> otherFields = new HashSet<>();

    /// @param packageName the compilation unit's package, dot-separated, empty for the default package
    /// @param types       the compilation unit's types by binary name relative to the package
    ///                    (`Outer$Nested`)
    ConstantEvaluator(String packageName, Map<String, TypeDeclaration<?>> types) {
        this.packageName = packageName;
        this.types = types;
        types.forEach((name, type) -> {
            boolean implicitlyConstant = type.isAnnotationDeclaration()
                    || type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof FieldDeclaration field) {
                    boolean constant = implicitlyConstant || field.isStatic() && field.isFinal();
                    for (VariableDeclarator variable : field.getVariables()) {
                        String key = name + "#" + variable.getNameAsString();
                        if (constant && variable.getInitializer().isPresent()) {
                            constants.put(key, variable.getInitializer().get());
                        } else {
                            otherFields.add(key);
                        }
                    }
                }
            }
        });
    }

    /// The value of a `String`-valued annotation element.
    ///
    /// @param context the type that declares the annotated member (relative binary name), for simple names
    String string(Expression value, String context) throws NotConstantException {
        Object result = evaluate(value, context, new HashSet<>());
        if (result instanceof String string) {
            return string;
        }
        throw new NotConstantException("not a String but " + result.getClass().getSimpleName());
    }

    /// Decodes the escape sequences of a string or char literal as written between its quotes.
    static String decodeEscapes(String raw) throws NotConstantException {
        try {
            return raw.translateEscapes();
        } catch (IllegalArgumentException e) {
            throw new NotConstantException("invalid escape sequence: " + e.getMessage());
        }
    }

    /// The value of a text block, given its content as JavaParser keeps it: from the line after the opening
    /// `"""` up to the closing delimiter.
    static String textBlock(String raw) throws NotConstantException {
        String content = raw.replace("\r\n", "\n").replace('\r', '\n');
        return decodeEscapes(content.stripIndent());
    }

    private Object evaluate(Expression expression, String context, Set<String> resolving)
            throws NotConstantException {
        if (expression instanceof StringLiteralExpr literal) {
            return decodeEscapes(literal.getValue());
        }
        if (expression instanceof TextBlockLiteralExpr literal) {
            return textBlock(literal.getValue());
        }
        if (expression instanceof CharLiteralExpr literal) {
            String value = decodeEscapes(literal.getValue());
            if (value.length() != 1) {
                throw new NotConstantException("invalid char literal");
            }
            return value.charAt(0);
        }
        if (expression instanceof IntegerLiteralExpr literal) {
            return literal.asNumber();
        }
        if (expression instanceof LongLiteralExpr literal) {
            return literal.asNumber();
        }
        if (expression instanceof DoubleLiteralExpr literal) {
            return floatingPoint(literal.getValue());
        }
        if (expression instanceof BooleanLiteralExpr literal) {
            return literal.getValue();
        }
        if (expression instanceof EnclosedExpr enclosed) {
            return evaluate(enclosed.getInner(), context, resolving);
        }
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            return plus(evaluate(binary.getLeft(), context, resolving),
                    evaluate(binary.getRight(), context, resolving));
        }
        if (expression instanceof UnaryExpr unary) {
            return unary(unary, evaluate(unary.getExpression(), context, resolving));
        }
        if (expression instanceof CastExpr cast && cast.getType().asString().matches("(java\\.lang\\.)?String")) {
            Object value = evaluate(cast.getExpression(), context, resolving);
            if (value instanceof String) {
                return value;
            }
            throw new NotConstantException("cast of a non-String value to String");
        }
        if (expression instanceof NameExpr name) {
            return simpleName(name.getNameAsString(), context, resolving);
        }
        if (expression instanceof FieldAccessExpr access) {
            return qualifiedName(access, context, resolving);
        }
        throw new NotConstantException("unsupported expression (" + expression.getClass().getSimpleName() + ")");
    }

    /// A `float` or `double` literal, decimal or hexadecimal. Only a `float` ends with `f` or
    /// `F`: a hexadecimal one must end with its binary exponent or a type suffix.
    private static Object floatingPoint(String literal) throws NotConstantException {
        String text = literal.replace("_", "");
        try {
            return text.toLowerCase(Locale.ROOT).endsWith("f")
                    ? (Object) Float.parseFloat(text)
                    : (Object) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new NotConstantException("invalid floating-point literal " + literal);
        }
    }

    /// `+`: string concatenation when either side is a String, else numeric addition (JLS §15.18).
    private static Object plus(Object left, Object right) throws NotConstantException {
        if (left instanceof String || right instanceof String) {
            return asString(left) + asString(right);
        }
        if (!isNumeric(left) || !isNumeric(right)) {
            throw new NotConstantException("unsupported operands of + (" + left.getClass().getSimpleName() + ", "
                    + right.getClass().getSimpleName() + ")");
        }
        if (left instanceof Double || right instanceof Double) {
            return asDouble(left) + asDouble(right);
        }
        if (left instanceof Float || right instanceof Float) {
            return (float) asDouble(left) + (float) asDouble(right);
        }
        if (left instanceof Long || right instanceof Long) {
            return asLong(left) + asLong(right);
        }
        return (int) asLong(left) + (int) asLong(right);
    }

    private static Object unary(UnaryExpr unary, Object operand) throws NotConstantException {
        boolean minus = unary.getOperator() == UnaryExpr.Operator.MINUS;
        if (!(minus || unary.getOperator() == UnaryExpr.Operator.PLUS) || !isNumeric(operand)) {
            throw new NotConstantException("unsupported unary operator " + unary.getOperator());
        }
        if (operand instanceof Double value) {
            return minus ? -value : value;
        }
        if (operand instanceof Float value) {
            return minus ? -value : value;
        }
        if (operand instanceof Long value) {
            return minus ? -value : value;
        }
        // int and char promote to int
        int value = (int) asLong(operand);
        return minus ? -value : value;
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Number || value instanceof Character;
    }

    private static long asLong(Object value) {
        return value instanceof Character c ? c : ((Number) value).longValue();
    }

    private static double asDouble(Object value) {
        return value instanceof Character c ? c : ((Number) value).doubleValue();
    }

    /// Java's string conversion of a constant: chars as themselves, numbers and booleans in their text form.
    private static String asString(Object value) {
        return String.valueOf(value);
    }

    private Object simpleName(String name, String context, Set<String> resolving) throws NotConstantException {
        for (String type = context; type != null; type = enclosing(type)) {
            String key = type + "#" + name;
            if (constants.containsKey(key)) {
                return constant(type, name, resolving);
            }
            if (otherFields.contains(key)) {
                throw new NotConstantException(name + " is not a static final field with an initializer");
            }
        }
        throw new NotConstantException(name + " is not a constant declared in this file");
    }

    private Object qualifiedName(FieldAccessExpr access, String context, Set<String> resolving)
            throws NotConstantException {
        String field = access.getNameAsString();
        Optional<String> scope = dottedName(access.getScope());
        if (scope.isEmpty()) {
            throw new NotConstantException("unsupported field access " + access);
        }
        String type = resolveType(scope.get(), context);
        if (type == null || !constants.containsKey(type + "#" + field)) {
            throw new NotConstantException(scope.get() + "." + field + " is not a constant declared in this file");
        }
        return constant(type, field, resolving);
    }

    private Object constant(String type, String field, Set<String> resolving) throws NotConstantException {
        String key = type + "#" + field;
        if (!resolving.add(key)) {
            throw new NotConstantException("circular constant " + key);
        }
        try {
            return evaluate(constants.get(key), type, resolving);
        } finally {
            resolving.remove(key);
        }
    }

    /// Resolves a type name written in the source to a type of this compilation unit: a member type of the context
    /// type or of one of its enclosing types, a top-level type, or a fully qualified name in this package.
    ///
    /// @return the relative binary name, or null when the type is not declared in this compilation unit
    private String resolveType(String dotted, String context) {
        boolean qualified = !packageName.isEmpty() && dotted.startsWith(packageName + ".");
        String[] segments = (qualified ? dotted.substring(packageName.length() + 1) : dotted).split("\\.");
        String base = null;
        if (!qualified) {
            for (String type = context; type != null && base == null; type = enclosing(type)) {
                if (types.containsKey(type + "$" + segments[0])) {
                    base = type + "$" + segments[0];
                }
            }
        }
        if (base == null && types.containsKey(segments[0])) {
            base = segments[0];
        }
        for (int i = 1; base != null && i < segments.length; i++) {
            base = types.containsKey(base + "$" + segments[i]) ? base + "$" + segments[i] : null;
        }
        return base;
    }

    private static Optional<String> dottedName(Expression scope) {
        if (scope instanceof NameExpr name) {
            return Optional.of(name.getNameAsString());
        }
        if (scope instanceof FieldAccessExpr access) {
            return dottedName(access.getScope()).map(prefix -> prefix + "." + access.getNameAsString());
        }
        return Optional.empty();
    }

    private static String enclosing(String type) {
        int dollar = type.lastIndexOf('$');
        return dollar < 0 ? null : type.substring(0, dollar);
    }
}
