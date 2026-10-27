package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Computes the effective dependency management of a BOM the way Maven 3 builds the model of a pom it reads from a
/// repository, so that a BOM that is not flattened (Quarkus 0.x and 1.x) gives what a build importing it manages:
///
/// 1. **Profiles** of each pom that Maven activates in a plain build, the same on every machine (no `-P`, no `-D`): a
///    property condition holds when it asks for a property to be absent (`!name`, or a `!value`), and
///    `activeByDefault` profiles count when no other profile of the pom is active. Their properties and managed
///    dependencies override the pom's own. A profile whose activation depends on the machine (JDK, OS, file, or a
///    property the JVM or the environment provides) is not applied; a warning names it when it manages dependencies or
///    sets a property the resolution reads.
/// 2. **Inheritance.** The pom and its parents, read from the repository. A pom's groupId and version default to its
///    parent's; properties merge, the child's value winning; managed dependencies merge by management key
///    (`groupId:artifactId:type:classifier`): a pom's own entries, then its parents' entries of other keys.
/// 3. **Interpolation** of the merged model, so inherited entries see the child's values: `${project.groupId}`,
///    `${project.artifactId}`, `${project.version}`, `${project.packaging}` and `${project.parent.groupId}`,
///    `${project.parent.artifactId}`, `${project.parent.version}` (also with the legacy `pom.` prefix, or unprefixed
///    after the properties), then the properties, resolved recursively; like Maven, a value that holds the reference
///    itself is passed over for the next one. An unknown reference stays as written.
/// 4. **Imports.** The `<type>pom</type><scope>import</scope>` entries, in declaration order (a pom's own before its
///    parents'), are replaced by the effective dependency management of the imported BOM, built the same way in the
///    imported BOM's own context, for the keys not managed yet: a pom's own and inherited entries beat imported ones,
///    and an earlier import beats a later one.
///
/// Like Maven, a merge keeps the last of a pom's own entries with one key (Maven warns about such duplicates) and the
/// first of the merged-in ones; a pom with neither parent entries nor imports keeps its list as written, and of
/// entries with the same id the first counts.
///
/// Where Maven fails to build the model, so does the resolution: parent, import and property reference cycles (also a
/// property cycle that no managed dependency reads, since Maven interpolates every property of the model), and a
/// managed dependency without a valid groupId and artifactId (Maven's validation of the model). A reference that no
/// pom defines stays as written in a managed dependency's version, type or classifier, as in Maven's effective model:
/// [Result#unresolved()] lists the entries that keep one, and [Result#describeUnresolved()] says where they come from.
/// The resolution fails instead where Maven may give such a reference a value that it does not know ([Elsewhere]),
/// and where a type or classifier keeps the text of [BomParser#QUARKUS_VERSION_PLACEHOLDER], which an id could not
/// tell from the placeholder.
///
/// An instance can be shared between threads: each pom is fetched once (a concurrent request for the same pom waits
/// for the first) and the effective dependency management of imported BOMs is kept.
public final class BomResolver {

    /// A `${...}` reference, also one that is never closed (Maven keeps both as written).
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{[^}]*}?");
    /// A closed `${...}` reference as interpolation reads it, up to the first `}`; the group is its expression.
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]*)}");
    /// Maven's characters of a groupId or artifactId.
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]+");
    /// A version without the characters Maven forbids in one (path separators and other reserved characters).
    private static final Pattern VERSION = Pattern.compile("[^\\\\/:\"<>|?*\\s]+");
    private static final int EXAMPLES = 3;

    /// Where parents and imported BOMs come from.
    @FunctionalInterface
    public interface PomSource {

        /// No pom at all: enough for a self-contained BOM such as a flattened one.
        PomSource NONE = (groupId, artifactId, version) -> Optional.empty();

        /// The pom `groupId:artifactId:version`; empty when the repository does not have it.
        Optional<byte[]> pom(String groupId, String artifactId, String version) throws IOException;
    }

    /// A managed dependency of a [Result].
    ///
    /// @param id         the [ManagedDep#id()]
    /// @param version    the managed version
    /// @param origin     the pom that declares the entry, `groupId:artifactId:version`
    /// @param via        the import of the resolved pom (or of one of its parents) that brought the entry in, null for
    ///                   entries of the pom and its parents
    /// @param unresolved the `${...}` references left in the coordinates or the version, in order
    public record Entry(String id, String version, String origin, String via, List<String> unresolved) {
    }

    /// The effective dependency management of a BOM.
    ///
    /// @param coordinates the resolved pom, `groupId:artifactId:version`
    /// @param entries     the managed dependencies that have a version, sorted by id
    /// @param parents     the parents of the pom, nearest first
    /// @param imports     the imported BOMs, directly or through parents and other imports, in resolution order
    /// @param poms        how many distinct poms the resolution read, the resolved one included
    /// @param profiles    the profiles applied that manage dependencies or set a property the resolution reads,
    ///                    `id of groupId:artifactId:version`
    /// @param warnings    what was ignored or skipped
    public record Result(String coordinates, List<Entry> entries, List<String> parents, List<String> imports,
                         int poms, List<String> profiles, List<String> warnings) {

        /// The entries as snapshot records.
        public List<ManagedDep> deps() {
            return entries.stream().map(entry -> new ManagedDep(entry.id(), entry.version())).toList();
        }

        /// How many entries came in through imports.
        public long imported() {
            return entries.stream().filter(entry -> entry.via() != null).count();
        }

        /// The entries that keep a `${...}` reference.
        public List<Entry> unresolved() {
            return entries.stream().filter(entry -> !entry.unresolved().isEmpty()).toList();
        }

        /// What [#unresolved()] holds, for a warning: how many entries keep a reference, then each reference with the
        /// pom that declares it, the import that brought it in and the first entries that hold it; empty when no entry
        /// keeps one.
        public Optional<String> describeUnresolved() {
            List<Entry> unresolved = unresolved();
            if (unresolved.isEmpty()) {
                return Optional.empty();
            }
            Map<String, List<String>> idsByCause = new TreeMap<>();
            for (Entry entry : unresolved) {
                String cause = String.join(", ", entry.unresolved()) + " (declared in " + entry.origin()
                        + (entry.via() == null ? "" : ", imported through " + entry.via()) + ")";
                idsByCause.computeIfAbsent(cause, key -> new ArrayList<>()).add(entry.id());
            }
            List<String> causes = new ArrayList<>();
            idsByCause.forEach((cause, ids) -> causes.add(cause + ": " + examples(ids)));
            return Optional.of(String.format(Locale.ROOT, "%s unresolved references: %s; neither the poms that "
                            + "declare them nor their parents define these properties",
                    unresolved.size() == 1 ? "1 managed dependency keeps" : unresolved.size()
                            + " managed dependencies keep", String.join("; ", causes)));
        }

        private static String examples(List<String> ids) {
            if (ids.size() <= EXAMPLES + 1) {
                return String.join(", ", ids);
            }
            return String.join(", ", ids.subList(0, EXAMPLES)) + " and " + (ids.size() - EXAMPLES) + " more";
        }
    }

    /// A managed dependency while models are built: as written until its model is interpolated.
    ///
    /// @param origin the pom that declares it
    /// @param via    the import of the model being built that brought it in, null for the model's own entries
    /// @param doubt  once interpolated, why Maven may give a reference that it keeps a value that the resolution does
    ///               not know (`keeps ${x}, which ...`); null when Maven keeps every one of them as written
    private record Managed(String groupId, String artifactId, String version, String type, String classifier,
                           String scope, String origin, String via, String doubt) {

        /// Maven's management key, `groupId:artifactId:type:classifier` (type `jar` when absent).
        String key() {
            return groupId + ":" + artifactId + ":" + (type != null ? type : "jar")
                    + (classifier != null ? ":" + classifier : "");
        }

        boolean isImport() {
            return "pom".equals(type) && "import".equals(scope);
        }

        String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }

        Managed via(String importId) {
            return new Managed(groupId, artifactId, version, type, classifier, scope, origin, importId, doubt);
        }

        Managed doubting(String why) {
            return new Managed(groupId, artifactId, version, type, classifier, scope, origin, via, why);
        }
    }

    /// Where Maven may find a value for a reference that no property of the model defines, which the resolution does
    /// not know: Maven keeps such a reference as written only on some machines, or not at all.
    private enum Elsewhere {
        /// An element of the model other than the coordinates, the packaging and the parent's coordinates, such as
        /// `${project.name}`, `${name}` or `${project.build.finalName}` (which the super POM sets).
        MODEL,
        /// A property of the JVM, of the environment or of Maven itself: `${java.version}`, `${env.HOME}`.
        MACHINE,
        /// A property that a profile depending on the machine sets.
        PROFILE
    }

    /// The text elements of Maven's model that the resolution does not read, which a reference reads as a whole.
    private static final Set<String> MODEL_TEXT = Set.of("childProjectUrlInheritAppendPath", "description", "id",
            "inceptionYear", "modelEncoding", "modelVersion", "name", "url");
    /// The elements of Maven's model with parts, which a reference reads along a path such as `build.finalName`.
    private static final Set<String> MODEL_PARTS = Set.of("build", "ciManagement", "contributors", "dependencies",
            "dependencyManagement", "developers", "distributionManagement", "issueManagement", "licenses",
            "mailingLists", "modules", "organization", "pluginRepositories", "pomFile", "prerequisites", "profiles",
            "projectDirectory", "properties", "reporting", "reports", "repositories", "scm");

    /// What the resolution needs of a pom's effective model.
    ///
    /// @param coordinates `groupId:artifactId:version`, interpolated
    /// @param managed     the effective dependency management, imports replaced, in Maven's order
    /// @param parents     its parents, nearest first
    /// @param imports     the BOMs it imports, directly or not, in resolution order
    /// @param poms        the poms read to build it, its own included
    /// @param profiles    the profiles applied to it that matter, its imports' included
    /// @param warnings    what was ignored
    private record Model(String coordinates, List<Managed> managed, List<String> parents, List<String> imports,
                         Set<String> poms, List<String> profiles, List<String> warnings) {
    }

    /// Whether Maven activates a profile through its `<activation>` conditions in a plain build.
    private enum Activated { YES, NO, DEPENDS_ON_THE_MACHINE }

    /// Properties the JVM, the operating system environment or Maven itself provide, which a plain build has.
    private static final List<String> MACHINE_PROPERTIES = List.of("env.", "java.", "jdk.", "os.", "user.", "file.",
            "line.", "path.", "sun.", "maven.");

    private final PomSource source;
    private final Map<String, CompletableFuture<Optional<Pom>>> fetched = new ConcurrentHashMap<>();
    private final Map<String, Model> importedModels = new ConcurrentHashMap<>();

    public BomResolver(PomSource source) {
        this.source = source;
    }

    /// The effective dependency management of a BOM pom; its parents and imported BOMs come from the [PomSource].
    ///
    /// @param pom        the BOM pom
    /// @param bomVersion the release version, for the classifier placeholder of [BomParser#id]
    /// @throws IOException when a parent or an imported BOM cannot be read or has no valid coordinates, on a parent,
    ///                     import or property reference cycle, when a managed dependency has no valid groupId and
    ///                     artifactId, when one keeps a reference that Maven may resolve where the resolution does not
    ///                     look ([Elsewhere]), or when its type or classifier keeps the text of the placeholder
    public Result resolve(byte[] pom, String bomVersion) throws IOException {
        Model model = build(Pom.parse(pom), List.of());
        Set<String> warnings = new LinkedHashSet<>(model.warnings());
        Map<String, Entry> byId = new TreeMap<>();
        List<String> doubts = new ArrayList<>();
        for (Managed managed : model.managed()) {
            // Maven keeps it as well (it may manage a scope or exclusions), but there is no version to list
            if (managed.version() == null) {
                warnings.add(String.format(Locale.ROOT, "skipping incomplete managed dependency %s:%s (declared in %s)",
                        managed.groupId(), managed.artifactId(), managed.origin()));
                continue;
            }
            String declared = " (declared in " + managed.origin()
                    + (managed.via() == null ? "" : ", imported through " + managed.via()) + ")";
            for (String value : new String[] {managed.type(), managed.classifier()}) {
                if (value != null && value.contains(BomParser.QUARKUS_VERSION_PLACEHOLDER)) {
                    throw new IOException("the managed dependency " + managed.groupId() + ":" + managed.artifactId()
                            + declared + " keeps " + value + " in its type or classifier, which an id could not tell "
                            + "from the placeholder " + BomParser.QUARKUS_VERSION_PLACEHOLDER + " of a classifier "
                            + "equal to the release version");
                }
            }
            String id = BomParser.id(managed.groupId(), managed.artifactId(), managed.type(), managed.classifier(),
                    bomVersion);
            List<String> references = new ArrayList<>();
            for (String value : new String[] {managed.groupId(), managed.artifactId(), managed.type(),
                    managed.classifier(), managed.version()}) {
                if (value != null) {
                    REFERENCE.matcher(value).results().map(MatchResult::group).forEach(references::add);
                }
            }
            Entry entry = new Entry(id, managed.version(), managed.origin(), managed.via(), List.copyOf(references));
            Entry previous = byId.putIfAbsent(id, entry);
            if (previous == null && managed.doubt() != null) {
                doubts.add("the managed dependency " + id + declared + " " + managed.doubt());
            }
            if (previous != null && !previous.version().equals(entry.version())) {
                warnings.add(String.format(Locale.ROOT, "%s managed twice (%s, %s); keeping the first", id,
                        previous.version(), entry.version()));
            }
        }
        if (!doubts.isEmpty()) {
            throw new IOException(doubts.getFirst() + (doubts.size() == 1 ? "" : "; " + (doubts.size() - 1)
                    + " more managed " + (doubts.size() == 2 ? "dependency keeps" : "dependencies keep")
                    + " such a reference"));
        }
        return new Result(model.coordinates(), List.copyOf(byId.values()), model.parents(), model.imports(),
                model.poms().size(), model.profiles(), List.copyOf(warnings));
    }

    /// Builds the effective model of a pom.
    ///
    /// @param importers the poms whose imports led here, outermost first (for cycle detection)
    private Model build(Pom pom, List<String> importers) throws IOException {
        List<Pom> lineage = lineage(pom);
        List<String> parents = new ArrayList<>();
        for (Pom child : lineage.subList(0, lineage.size() - 1)) {
            parents.add(child.parent().toString());
        }
        String groupId = lineage.stream().map(Pom::groupId).filter(Objects::nonNull).findFirst().orElse(null);
        String version = lineage.stream().map(Pom::version).filter(Objects::nonNull).findFirst().orElse(null);
        List<List<Pom.Profile>> active = lineage.stream().map(BomResolver::activeProfiles).toList();
        Map<String, String> properties = new HashMap<>();
        for (int i = lineage.size() - 1; i >= 0; i--) {
            properties.putAll(lineage.get(i).properties());
            active.get(i).forEach(profile -> properties.putAll(profile.properties()));
        }
        Map<String, String> modelValues = new HashMap<>();
        putIfNotNull(modelValues, "groupId", groupId);
        putIfNotNull(modelValues, "artifactId", pom.artifactId());
        putIfNotNull(modelValues, "version", version);
        modelValues.put("packaging", Objects.requireNonNullElse(pom.packaging(), "jar"));
        if (pom.parent() != null) {
            putIfNotNull(modelValues, "parent.groupId", pom.parent().groupId());
            putIfNotNull(modelValues, "parent.artifactId", pom.parent().artifactId());
            putIfNotNull(modelValues, "parent.version", pom.parent().version());
        }
        String owner = groupId + ":" + pom.artifactId() + ":" + version;
        Interpolator interpolator = new Interpolator(owner, modelValues, properties, pom.parent() != null);
        String coordinates = interpolator.apply(groupId) + ":" + interpolator.apply(pom.artifactId()) + ":"
                + interpolator.apply(version);

        List<String> origins = new ArrayList<>(List.of(coordinates));
        origins.addAll(parents);
        // what the profiles that only some machines activate set: Maven's value is not known here
        Map<String, String> machineProfiles = new HashMap<>();
        for (int i = 0; i < lineage.size(); i++) {
            for (Pom.Profile profile : lineage.get(i).profiles()) {
                if (!active.get(i).contains(profile)
                        && activated(profile.activation()) == Activated.DEPENDS_ON_THE_MACHINE) {
                    String which = describe(profile, origins.get(i));
                    profile.properties().keySet().forEach(property -> machineProfiles.putIfAbsent(property, which));
                }
            }
        }
        List<List<Managed>> declared = new ArrayList<>();
        for (int i = 0; i < lineage.size(); i++) {
            List<Managed> own = managed(lineage.get(i).managed(), origins.get(i));
            for (Pom.Profile profile : active.get(i)) {
                own = injectProfile(own, managed(profile.managed(), origins.get(i)));
            }
            declared.add(own);
        }
        List<Managed> managed = new ArrayList<>();
        for (Managed raw : inherit(declared)) {
            Managed entry = new Managed(interpolator.apply(raw.groupId()), interpolator.apply(raw.artifactId()),
                    interpolator.apply(raw.version()), blankToNull(interpolator.apply(raw.type())),
                    blankToNull(interpolator.apply(raw.classifier())), interpolator.apply(raw.scope()),
                    raw.origin(), null, null);
            if (!entry.isImport()) {
                requireValidIds(entry);
                entry = entry.doubting(doubt(entry, coordinates, pom.parent() != null, machineProfiles));
            }
            managed.add(entry);
        }
        // Maven interpolates every property of the model: a cycle fails also where no managed dependency reads it
        Interpolator everyProperty = new Interpolator(owner, modelValues, properties, pom.parent() != null);
        for (String property : new TreeSet<>(properties.keySet())) {
            everyProperty.apply(properties.get(property));
        }

        List<String> profiles = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (int i = 0; i < lineage.size(); i++) {
            for (Pom.Profile profile : lineage.get(i).profiles()) {
                boolean matters = !profile.managed().isEmpty()
                        || profile.properties().keySet().stream().anyMatch(interpolator::read);
                if (active.get(i).contains(profile)) {
                    if (matters) {
                        profiles.add(profile.id() + " of " + origins.get(i));
                    }
                    continue;
                }
                if (activated(profile.activation()) != Activated.DEPENDS_ON_THE_MACHINE) {
                    continue;
                }
                String which = describe(profile, origins.get(i));
                if (!profile.managed().isEmpty()) {
                    warnings.add(String.format(Locale.ROOT, "profiles that depend on the machine are not applied: "
                            + "%s manages %d %s", which, profile.managed().size(),
                            profile.managed().size() == 1 ? "dependency" : "dependencies"));
                }
                for (String property : profile.properties().keySet()) {
                    if (interpolator.read(property)) {
                        warnings.add(String.format(Locale.ROOT, "profiles that depend on the machine are not "
                                + "applied: %s sets the property %s, which the managed dependencies of %s use", which,
                                property, coordinates));
                    }
                }
            }
        }

        Set<String> poms = new LinkedHashSet<>(origins);
        List<Managed> own = managed.stream().filter(entry -> !entry.isImport()).toList();
        List<Managed> imports = managed.stream().filter(Managed::isImport).toList();
        if (imports.isEmpty()) {
            return new Model(coordinates, own, List.copyOf(parents), List.of(), poms, List.copyOf(profiles),
                    List.copyOf(warnings));
        }
        List<String> chain = new ArrayList<>(importers);
        chain.add(coordinates);
        Map<String, Managed> byKey = new LinkedHashMap<>();
        own.forEach(entry -> byKey.put(entry.key(), entry));
        Set<String> imported = new LinkedHashSet<>();
        for (Managed entry : imports) {
            String id = entry.coordinates();
            if (!valid(entry.groupId(), entry.artifactId(), entry.version())) {
                throw new IOException(entry.origin() + " imports " + id + ", whose coordinates are incomplete, keep "
                        + "an unresolved reference or are no valid Maven coordinates");
            }
            if (chain.contains(id)) {
                throw new IOException("the BOM imports form a cycle: " + String.join(" -> ", chain) + " -> " + id);
            }
            Model model = imported(entry, chain);
            imported.add(id);
            imported.addAll(model.imports());
            poms.addAll(model.poms());
            model.profiles().stream().filter(profile -> !profiles.contains(profile)).forEach(profiles::add);
            warnings.addAll(model.warnings());
            for (Managed fromImport : model.managed()) {
                byKey.putIfAbsent(fromImport.key(), fromImport.via(id));
            }
        }
        return new Model(coordinates, List.copyOf(byKey.values()), List.copyOf(parents), List.copyOf(imported), poms,
                List.copyOf(profiles), List.copyOf(warnings));
    }

    /// Fails, as Maven's validation of the effective model does, on a managed dependency whose groupId or artifactId
    /// is missing or holds other characters than Maven's id characters, such as a reference that no pom defines. Import
    /// entries are not managed dependencies: they are replaced by the BOMs they import.
    private static void requireValidIds(Managed entry) throws IOException {
        String[][] ids = {{"groupId", entry.groupId()}, {"artifactId", entry.artifactId()}};
        for (String[] id : ids) {
            if (id[1] == null || id[1].isEmpty()) {
                throw new IOException(String.format(Locale.ROOT, "%s manages %s:%s without %s, which Maven does not "
                        + "accept", entry.origin(), orUnknown(entry.groupId()), orUnknown(entry.artifactId()), id[0]));
            }
            if (!ID.matcher(id[1]).matches()) {
                throw new IOException(String.format(Locale.ROOT, "%s manages %s:%s, whose %s %s is no valid Maven id%s",
                        entry.origin(), entry.groupId(), entry.artifactId(), id[0], id[1],
                        id[1].contains("${") ? " (it keeps a reference that no pom defines)" : ""));
            }
        }
    }

    private static List<Managed> managed(List<Pom.Dependency> dependencies, String origin) {
        List<Managed> managed = new ArrayList<>();
        for (Pom.Dependency d : dependencies) {
            managed.add(new Managed(d.groupId(), d.artifactId(), d.version(), d.type(), d.classifier(), d.scope(),
                    origin, null, null));
        }
        return managed;
    }

    /// Why Maven may give a reference that the version, type or classifier of an interpolated entry keeps a value that
    /// the resolution does not know ([Managed#doubt()]); null when Maven keeps every one of them as written.
    ///
    /// @param coordinates     the model that interpolated the entry
    /// @param parent          whether that model has a parent
    /// @param machineProfiles the properties that the profiles of its lineage depending on the machine set, each with
    ///                        the first such profile
    private static String doubt(Managed entry, String coordinates, boolean parent,
            Map<String, String> machineProfiles) {
        for (String value : new String[] {entry.version(), entry.type(), entry.classifier()}) {
            Matcher matcher = EXPRESSION.matcher(Objects.toString(value, ""));
            while (matcher.find()) {
                String expression = matcher.group(1);
                String name = expression.startsWith(".") ? expression.substring(1) : expression;
                Elsewhere where = elsewhere(name, parent, machineProfiles.keySet());
                if (where != null) {
                    return "keeps ${" + expression + "}, " + switch (where) {
                        case MODEL -> "which Maven reads from the model of " + coordinates + ", beyond the "
                                + "coordinates, packaging, parent and properties that the resolution reads";
                        case MACHINE -> "which Maven takes from the machine that builds: a property of the JVM, of "
                                + "the environment or of Maven itself";
                        case PROFILE -> "which the " + machineProfiles.get(name) + " sets on the machines that "
                                + "activate it, a profile that the resolution does not apply";
                    };
                }
            }
        }
        return null;
    }

    /// Where Maven may find a value for the reference `${name}` that no property defines, a value that the resolution
    /// does not know; null when Maven keeps such a reference as written in every plain build. Maven reads the model
    /// along the reference's path, with or without the `project.` or `pom.` prefix (a text element only as a whole:
    /// `${version.jdk-misc}` reads nothing), and an unprefixed reference also among the properties of the machine.
    ///
    /// @param parent     whether the model has a parent
    /// @param machineSet the properties that profiles depending on the machine set
    private static Elsewhere elsewhere(String name, boolean parent, Set<String> machineSet) {
        String path = Interpolator.unprefixed(name);
        String element = path.split("[.\\[(]", 2)[0];
        if (MODEL_PARTS.contains(element) || element.equals(path) && MODEL_TEXT.contains(element)
                || element.equals("parent") && parent) {
            return Elsewhere.MODEL;
        }
        if (path.equals(name) && MACHINE_PROPERTIES.stream().anyMatch(name::startsWith)) {
            return Elsewhere.MACHINE;
        }
        return machineSet.contains(name) ? Elsewhere.PROFILE : null;
    }

    /// The profiles of a pom that a plain Maven build activates on every machine, in declaration order: those whose
    /// activation conditions hold, else those active by default.
    private static List<Pom.Profile> activeProfiles(Pom pom) {
        List<Pom.Profile> active = new ArrayList<>();
        List<Pom.Profile> byDefault = new ArrayList<>();
        for (Pom.Profile profile : pom.profiles()) {
            if (activated(profile.activation()) == Activated.YES) {
                active.add(profile);
            } else if (profile.activation() != null && profile.activation().activeByDefault()) {
                byDefault.add(profile);
            }
        }
        return active.isEmpty() ? byDefault : active;
    }

    /// Whether the activation conditions of a profile hold in a plain build; Maven requires all of them. A property
    /// condition is evaluated with no property set: `!name` and a `!value` hold, a plain name or value does not.
    private static Activated activated(Pom.Activation activation) {
        if (activation == null) {
            return Activated.NO;
        }
        boolean machine = activation.jdk() != null || activation.os() || activation.file();
        String name = activation.propertyName();
        if (name != null) {
            String property = name.startsWith("!") ? name.substring(1) : name;
            if (property.isEmpty()) {
                return Activated.NO;
            }
            if (MACHINE_PROPERTIES.stream().anyMatch(property::startsWith)) {
                machine = true;
            } else {
                String value = activation.propertyValue();
                boolean holds = value != null ? value.startsWith("!") : name.startsWith("!");
                if (!holds) {
                    return Activated.NO;
                }
                if (!machine) {
                    return Activated.YES;
                }
            }
        }
        return machine ? Activated.DEPENDS_ON_THE_MACHINE : Activated.NO;
    }

    /// `profile <id> of <pom> (<conditions>)`, for a profile that depends on the machine.
    private static String describe(Pom.Profile profile, String origin) {
        return "profile " + profile.id() + " of " + origin + " (" + conditions(profile.activation()) + ")";
    }

    private static String conditions(Pom.Activation activation) {
        List<String> conditions = new ArrayList<>();
        if (activation.jdk() != null) {
            conditions.add("JDK " + activation.jdk());
        }
        if (activation.os()) {
            conditions.add("OS");
        }
        if (activation.file()) {
            conditions.add("file");
        }
        if (activation.propertyName() != null) {
            conditions.add("property " + activation.propertyName()
                    + (activation.propertyValue() != null ? "=" + activation.propertyValue() : ""));
        }
        return String.join(", ", conditions);
    }

    /// A pom's managed dependencies with an active profile's injected as Maven does: a profile entry replaces the
    /// pom's entry of the same key in place, the others are appended.
    private static List<Managed> injectProfile(List<Managed> own, List<Managed> profile) {
        if (profile.isEmpty()) {
            return own;
        }
        Map<String, Managed> byKey = new LinkedHashMap<>();
        own.forEach(entry -> byKey.put(entry.key(), entry));
        profile.forEach(entry -> byKey.put(entry.key(), entry));
        return List.copyOf(byKey.values());
    }

    /// The effective model of an imported BOM, built once.
    private Model imported(Managed entry, List<String> chain) throws IOException {
        String id = entry.coordinates();
        Model model = importedModels.get(id);
        if (model == null) {
            Pom pom = fetch(entry.groupId(), entry.artifactId(), entry.version())
                    .orElseThrow(() -> new IOException("the BOM " + id + " imported by " + entry.origin()
                            + " is not in the repository"));
            model = build(pom, chain);
            importedModels.putIfAbsent(id, model);
        }
        return model;
    }

    /// The pom followed by its parents, nearest first.
    private List<Pom> lineage(Pom pom) throws IOException {
        List<Pom> lineage = new ArrayList<>(List.of(pom));
        Pom.Coordinates parent = pom.parent();
        String child = (pom.groupId() != null || parent == null ? pom.groupId() : parent.groupId()) + ":"
                + pom.artifactId() + ":" + (pom.version() != null || parent == null ? pom.version() : parent.version());
        Set<String> seen = new LinkedHashSet<>(List.of(child));
        for (Pom current = pom; current.parent() != null; ) {
            Pom.Coordinates coordinates = current.parent();
            String id = coordinates.toString();
            if (!valid(coordinates.groupId(), coordinates.artifactId(), coordinates.version())) {
                throw new IOException(child + " has an incomplete parent, one with a property or one whose "
                        + "coordinates are no valid Maven coordinates: " + id);
            }
            if (!seen.add(id)) {
                throw new IOException("the parents form a cycle: " + String.join(" -> ", seen) + " -> " + id);
            }
            String of = child;
            current = fetch(coordinates.groupId(), coordinates.artifactId(), coordinates.version())
                    .orElseThrow(() -> new IOException("the parent " + id + " of " + of + " is not in the repository"));
            lineage.add(current);
            child = id;
        }
        return lineage;
    }

    /// Managed dependencies after inheritance, merged as Maven does from the topmost parent down: a pom's own
    /// entries, then the inherited ones of other keys (keys as written, before interpolation). Merging keeps, of a
    /// pom's own entries with one key, the position of the first and the last entry, and of the inherited ones the
    /// first; a pom that inherits no entry keeps its list as written.
    ///
    /// @param declared each pom's own entries, the pom first and its topmost parent last
    private static List<Managed> inherit(List<List<Managed>> declared) {
        List<Managed> merged = declared.getLast();
        for (int i = declared.size() - 2; i >= 0; i--) {
            if (!merged.isEmpty()) {
                Map<String, Managed> byKey = new LinkedHashMap<>();
                declared.get(i).forEach(entry -> byKey.put(entry.key(), entry));
                merged.forEach(entry -> byKey.putIfAbsent(entry.key(), entry));
                merged = List.copyOf(byKey.values());
            } else {
                merged = declared.get(i);
            }
        }
        return merged;
    }

    /// Whether a parent or an imported BOM can be looked up: complete, without a `${...}` reference, with Maven's id
    /// characters and a version Maven accepts, and no `.` or `..` part, so that a pom cannot name a file outside the
    /// repository folder.
    private static boolean valid(String groupId, String artifactId, String version) {
        return groupId != null && artifactId != null && version != null && ID.matcher(groupId).matches()
                && ID.matcher(artifactId).matches() && VERSION.matcher(version).matches()
                && !REFERENCE.matcher(version).find() && !artifactId.equals(".") && !artifactId.equals("..")
                && !version.equals(".") && !version.equals("..");
    }

    /// The pom `groupId:artifactId:version`, read once. Whatever the fetch throws, also an [Error], reaches the threads
    /// that wait for it, so that none waits forever.
    private Optional<Pom> fetch(String groupId, String artifactId, String version) throws IOException {
        String id = groupId + ":" + artifactId + ":" + version;
        CompletableFuture<Optional<Pom>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<Pom>> earlier = fetched.putIfAbsent(id, mine);
        if (earlier != null) {
            return await(earlier, id);
        }
        try {
            Optional<byte[]> content = source.pom(groupId, artifactId, version);
            Optional<Pom> pom = content.isEmpty() ? Optional.empty() : Optional.of(parse(content.get(), id));
            mine.complete(pom);
            return pom;
        } catch (IOException | RuntimeException | Error e) {
            fetched.remove(id, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private static Pom parse(byte[] content, String id) throws IOException {
        try {
            return Pom.parse(content);
        } catch (IOException e) {
            throw new IOException(id + ": " + e.getMessage(), e);
        }
    }

    /// The outcome of the fetch of another thread; its failure as it was thrown, so that a stop of the requests
    /// ([Http.StoppedException]) stays one.
    private static <T> T await(CompletableFuture<T> future, String id) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the pom " + id);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "?" : value;
    }

    /// Maven's model interpolation for one effective model: for `${x}`, the model value when `x` is `project.y` or
    /// `pom.y`, else the property `x`, else the unprefixed model value; the value found is interpolated in turn. Like
    /// Maven, it passes over a value that holds the reference itself for the next one. An unknown reference stays as
    /// written; a reference cycle fails, and so does a reference whose only values hold it, unless Maven may find
    /// another value in the model or on the machine ([Elsewhere]). A profile that only some machines activate is not
    /// applied, as everywhere in the resolution.
    private static final class Interpolator {

        private static final List<String> PREFIXES = List.of("project.", "pom.");

        private final String owner;
        private final Map<String, String> modelValues;
        private final Map<String, String> properties;
        private final boolean parent;
        private final Set<String> read = new HashSet<>();

        /// @param parent whether the model has a parent
        Interpolator(String owner, Map<String, String> modelValues, Map<String, String> properties, boolean parent) {
            this.owner = owner;
            this.modelValues = modelValues;
            this.properties = properties;
            this.parent = parent;
        }

        String apply(String value) throws IOException {
            return value == null ? null : interpolate(value, new ArrayList<>());
        }

        /// Whether a property of that name was looked up.
        boolean read(String property) {
            return read.contains(property);
        }

        private String interpolate(String value, List<String> resolving) throws IOException {
            StringBuilder result = new StringBuilder();
            int from = 0;
            for (int start = value.indexOf("${"); start >= 0; start = value.indexOf("${", from)) {
                int end = value.indexOf('}', start + 2);
                if (end < 0) {
                    break;
                }
                String resolved = lookup(value.substring(start + 2, end), resolving);
                result.append(value, from, start).append(resolved != null ? resolved : value.substring(start, end + 1));
                from = end + 1;
            }
            return result.append(value, from, value.length()).toString();
        }

        private String lookup(String expression, List<String> resolving) throws IOException {
            String name = expression.startsWith(".") ? expression.substring(1) : expression;
            String key = unprefixed(name);
            if (resolving.contains(key)) {
                throw cycle(resolving, key);
            }
            // the values the resolution knows, in Maven's order: the model value of a prefixed name, the property, the
            // model value of an unprefixed name
            List<Supplier<String>> sources = List.of(() -> key.equals(name) ? null : modelValues.get(key),
                    () -> property(name), () -> modelValues.get(name));
            String itself = "${" + expression + "}";
            boolean passedOver = false;
            for (Supplier<String> source : sources) {
                String value = source.get();
                if (value != null && value.contains(itself)) {
                    passedOver = true;
                } else if (value != null) {
                    resolving.add(key);
                    try {
                        return interpolate(value, resolving);
                    } finally {
                        resolving.removeLast();
                    }
                }
            }
            if (passedOver && elsewhere(name, parent, Set.of()) == null) {
                List<String> path = new ArrayList<>(resolving);
                path.add(key);
                throw cycle(path, key);
            }
            return null;
        }

        private String property(String name) {
            read.add(name);
            return properties.get(name);
        }

        private IOException cycle(List<String> path, String key) {
            return new IOException(owner + ": the property references form a cycle: " + String.join(" -> ", path)
                    + " -> " + key);
        }

        private static String unprefixed(String name) {
            for (String prefix : PREFIXES) {
                if (name.startsWith(prefix)) {
                    return name.substring(prefix.length());
                }
            }
            return name;
        }
    }
}
