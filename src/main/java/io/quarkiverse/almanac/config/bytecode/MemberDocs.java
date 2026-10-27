package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.sourcedoc.DocAnnotationSource;
import io.quarkiverse.almanac.config.sourcedoc.DocAnnotations;
import io.quarkiverse.almanac.maven.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/// The `@ConfigDoc*` annotations that pre-3.14 class files lack, looked up once per member from a [DocAnnotationSource]
///. A member whose lookup fails counts as having none. The failures
/// become one warning per declaring type, or a single warning when every lookup failed (typically offline without
/// the cached `-sources` jars), in which case the result equals a read of the class files only. A lookup refused
/// because requests stopped ([Http.StoppedException]) is no such failure: it ends the read. Thread-safe.
final class MemberDocs {

    /// Knows no annotations, never consults a source.
    static final MemberDocs NONE = new MemberDocs(DocAnnotationSource.NONE);

    /// Start of every warning about missing doc annotations, also used for `--doc-annotations none`.
    static final String UNAVAILABLE = "doc annotations unavailable";

    /// What the reader loses without the annotations, for warnings.
    static final String CONSEQUENCE = "the class files of releases before " + BytecodeConfigReader.RUNTIME_DOC_ANNOTATIONS
            + " lack @ConfigDocDefault, @ConfigDocMapKey and @ConfigDocIgnore, so map keys are named after members, "
            + "doc-only defaults are missing and ignored members appear";

    private final DocAnnotationSource source;
    private final Map<String, DocAnnotations> cache = new ConcurrentHashMap<>();
    private final Set<String> owners = ConcurrentHashMap.newKeySet();
    private final Map<String, String> failures = new ConcurrentSkipListMap<>();

    MemberDocs(DocAnnotationSource source) {
        this.source = source;
    }

    /// Whether a real source is consulted (not [DocAnnotationSource#NONE]).
    boolean active() {
        return source != DocAnnotationSource.NONE;
    }

    /// The source consulted.
    DocAnnotationSource source() {
        return source;
    }

    /// The annotations of one member.
    ///
    /// @param ownerInternalName internal name of the declaring type
    /// @param member            field or method name
    /// @return never null
    /// @throws Http.StoppedException when requests stopped: the lookup was refused, so the release cannot be read
    ///                               completely
    DocAnnotations get(String ownerInternalName, String member) throws Http.StoppedException {
        if (!active()) {
            return DocAnnotations.NONE;
        }
        String key = ownerInternalName + "#" + member;
        DocAnnotations cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        owners.add(ownerInternalName);
        DocAnnotations found;
        try {
            found = source.get(ownerInternalName, member);
        } catch (Http.StoppedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            failures.putIfAbsent(ownerInternalName, e.toString());
            found = null;
        }
        DocAnnotations result = found != null ? found : DocAnnotations.NONE;
        DocAnnotations previous = cache.putIfAbsent(key, result);
        return previous != null ? previous : result;
    }

    /// Number of declaring types looked up so far.
    int ownersLookedUp() {
        return owners.size();
    }

    /// Number of declaring types whose annotations could not be read.
    int ownersFailed() {
        return failures.size();
    }

    /// The lookup failures: one warning per declaring type whose annotations could not be read, sorted, or a single
    /// warning naming the first failure when no lookup succeeded.
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (failures.isEmpty()) {
            return warnings;
        }
        if (failures.size() == owners.size()) {
            Map.Entry<String, String> first = failures.entrySet().iterator().next();
            warnings.add(UNAVAILABLE + ": the lookup failed for all " + failures.size()
                    + " types that declare config members (first: " + ClassFile.canonicalName(first.getKey()) + ": "
                    + first.getValue() + "); " + CONSEQUENCE);
            return warnings;
        }
        failures.forEach((owner, failure) -> warnings.add(UNAVAILABLE + " for " + ClassFile.canonicalName(owner)
                + " (" + failure + "); read from the class file only"));
        return warnings;
    }
}
