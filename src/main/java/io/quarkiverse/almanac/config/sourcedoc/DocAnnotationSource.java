package io.quarkiverse.almanac.config.sourcedoc;

import java.io.IOException;

/// The `@ConfigDoc*` annotations of config members as written in the source code.
///
/// Before Quarkus 3.14, `ConfigDocDefault`, `ConfigDocMapKey`, `ConfigDocIgnore` and
/// `ConfigDocSection` had SOURCE retention, so class files do not carry them. The bytecode reader asks this source for them when it reads such a release.
///
/// Implementations are thread-safe.
public interface DocAnnotationSource {

    /// Knows no annotations; used for releases whose class files carry them.
    DocAnnotationSource NONE = (ownerInternalName, memberName) -> DocAnnotations.NONE;

    /// The doc annotations on one member of a config root or group.
    ///
    /// @param ownerInternalName internal name of the class or interface that declares the member, e.g.
    ///                          `io/quarkus/runtime/logging/LogConfig$CategoryConfig`
    /// @param memberName        field name (legacy `@ConfigRoot`/`@ConfigGroup` classes) or method name
    ///                          (`@ConfigMapping` interfaces)
    /// @return never null; [DocAnnotations#NONE] when the member has none or its source cannot be read
    DocAnnotations get(String ownerInternalName, String memberName) throws IOException;
}
