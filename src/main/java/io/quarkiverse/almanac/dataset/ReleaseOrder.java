package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.maven.QuarkusVersion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// The order of releases in the dataset, which is also the order of its version-keyed lines (`releases.jsonl`,
/// `config-meta.jsonl`) and of every change-point history: the version order of `QuarkusVersion`; versions that compare
/// equal there (`3.0.0` and `3.0.0.Final`) by text; versions that do not parse after all others, by text. It is a
/// total order consistent with `equals`.
public final class ReleaseOrder {

    /// Compares release version strings in dataset order.
    public static final Comparator<String> COMPARATOR = ReleaseOrder::compare;

    private ReleaseOrder() {
    }

    /// Compares two release versions in dataset order.
    public static int compare(String a, String b) {
        Optional<QuarkusVersion> va = QuarkusVersion.tryParse(a);
        Optional<QuarkusVersion> vb = QuarkusVersion.tryParse(b);
        if (va.isPresent() && vb.isPresent()) {
            int c = va.get().compareTo(vb.get());
            if (c != 0) {
                return c;
            }
        } else if (va.isPresent() != vb.isPresent()) {
            return va.isPresent() ? -1 : 1;
        }
        return a.compareTo(b);
    }

    /// The versions sorted in dataset order.
    public static List<String> sorted(Collection<String> versions) {
        List<String> sorted = new ArrayList<>(versions);
        sorted.sort(COMPARATOR);
        return sorted;
    }
}
