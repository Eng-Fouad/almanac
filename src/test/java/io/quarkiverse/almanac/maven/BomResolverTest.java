package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [BomResolver] on small synthetic poms, one rule of Maven's model building at a time.
class BomResolverTest {

    /// A flattened BOM as Quarkus 2.x and later publish it.
    private static final String FLATTENED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-bom</artifactId>
              <version>3.40.1</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom-quarkus-platform-descriptor</artifactId>
                    <version>3.40.1</version>
                    <type>json</type>
                    <classifier>3.40.1</classifier>
                  </dependency>
                  <dependency>
                    <groupId>io.vertx</groupId>
                    <artifactId>vertx-core</artifactId>
                    <version>4.5.21</version>
                    <exclusions>
                      <exclusion>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-codec-dns</artifactId>
                      </exclusion>
                    </exclusions>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>3.40.1</version>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>3.40.1</version>
                    <type>test-jar</type>
                    <classifier>tests</classifier>
                  </dependency>
                  <dependency>
                    <groupId>com.google.protobuf</groupId>
                    <artifactId>protoc</artifactId>
                    <version>4.35.0</version>
                    <type>exe</type>
                    <classifier>linux-x86_64</classifier>
                  </dependency>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>shaded</artifactId>
                    <version>1.0</version>
                    <type>jar</type>
                    <classifier>shaded</classifier>
                  </dependency>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>no-version</artifactId>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    /// Poms by `groupId:artifactId:version`, counting how often each is read.
    private static final class Repo implements BomResolver.PomSource {

        private final Map<String, String> poms = new HashMap<>();
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();

        Repo add(String coordinates, String pom) {
            poms.put(coordinates, pom);
            return this;
        }

        int reads(String coordinates) {
            return reads.getOrDefault(coordinates, new AtomicInteger()).get();
        }

        @Override
        public Optional<byte[]> pom(String groupId, String artifactId, String version) {
            String coordinates = groupId + ":" + artifactId + ":" + version;
            reads.computeIfAbsent(coordinates, key -> new AtomicInteger()).incrementAndGet();
            return Optional.ofNullable(poms.get(coordinates)).map(pom -> pom.getBytes(StandardCharsets.UTF_8));
        }
    }

    /// A pom: the coordinates `g:a:v` (an empty part is not written, so it is inherited), then the other elements.
    private static String project(String coordinates, String... elements) {
        String[] parts = coordinates.split(":", -1);
        StringBuilder pom = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n");
        for (String element : elements) {
            if (element.startsWith("<parent>")) {
                pom.append("  ").append(element).append('\n');
            }
        }
        String[] names = {"groupId", "artifactId", "version"};
        for (int i = 0; i < names.length; i++) {
            if (!parts[i].isEmpty()) {
                pom.append("  <").append(names[i]).append('>').append(parts[i]).append("</").append(names[i])
                        .append(">\n");
            }
        }
        for (String element : elements) {
            if (!element.startsWith("<parent>")) {
                pom.append("  ").append(element).append('\n');
            }
        }
        return pom.append("</project>\n").toString();
    }

    private static String parent(String coordinates) {
        String[] parts = coordinates.split(":");
        return "<parent><groupId>" + parts[0] + "</groupId><artifactId>" + parts[1] + "</artifactId><version>"
                + parts[2] + "</version></parent>";
    }

    /// `<properties>` from `name=value` pairs.
    private static String properties(String... pairs) {
        StringBuilder properties = new StringBuilder("<properties>");
        for (String pair : pairs) {
            String name = pair.substring(0, pair.indexOf('='));
            properties.append('<').append(name).append('>').append(pair.substring(pair.indexOf('=') + 1))
                    .append("</").append(name).append('>');
        }
        return properties.append("</properties>").toString();
    }

    /// `<dependencyManagement>` of the given `<dependency>` elements.
    private static String managed(String... dependencies) {
        return "<dependencyManagement><dependencies>" + String.join("", dependencies)
                + "</dependencies></dependencyManagement>";
    }

    /// A managed dependency `g:a:v` or `g:a:type:classifier:v` (empty type or classifier: not written).
    private static String dep(String coordinates) {
        String[] parts = coordinates.split(":", -1);
        StringBuilder dependency = new StringBuilder("<dependency><groupId>").append(parts[0])
                .append("</groupId><artifactId>").append(parts[1]).append("</artifactId>");
        String version = parts[parts.length - 1];
        if (parts.length == 5) {
            if (!parts[2].isEmpty()) {
                dependency.append("<type>").append(parts[2]).append("</type>");
            }
            if (!parts[3].isEmpty()) {
                dependency.append("<classifier>").append(parts[3]).append("</classifier>");
            }
        }
        return dependency.append("<version>").append(version).append("</version></dependency>").toString();
    }

    /// An import-scope BOM entry.
    private static String bomImport(String coordinates) {
        String[] parts = coordinates.split(":");
        return "<dependency><groupId>" + parts[0] + "</groupId><artifactId>" + parts[1] + "</artifactId><version>"
                + parts[2] + "</version><type>pom</type><scope>import</scope></dependency>";
    }

    private static BomResolver.Result resolve(BomResolver.PomSource source, String pom) throws IOException {
        return new BomResolver(source).resolve(pom.getBytes(StandardCharsets.UTF_8), "1");
    }

    private static Map<String, String> versions(BomResolver.Result result) {
        Map<String, String> versions = new HashMap<>();
        result.deps().forEach(dep -> versions.put(dep.id(), dep.v()));
        return versions;
    }

    @Test
    void readsAFlattenedBomAsWritten() throws IOException {
        BomResolver.Result result = new BomResolver(BomResolver.PomSource.NONE)
                .resolve(FLATTENED.getBytes(StandardCharsets.UTF_8), "3.40.1");
        assertEquals(List.of(
                new ManagedDep("com.google.protobuf:protoc:exe:linux-x86_64", "4.35.0"),
                new ManagedDep("io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}", "3.40.1"),
                new ManagedDep("io.quarkus:quarkus-core", "3.40.1"),
                new ManagedDep("io.quarkus:quarkus-core:test-jar:tests", "3.40.1"),
                new ManagedDep("io.vertx:vertx-core", "4.5.21"),
                new ManagedDep("org.example:shaded:shaded", "1.0")), result.deps());
        assertEquals(List.of(), result.unresolved(), "the classifier placeholder is no unresolved reference");
        assertEquals("io.quarkus:quarkus-bom:3.40.1", result.coordinates());
        assertEquals(List.of(), result.parents());
        assertEquals(List.of(), result.imports());
        assertEquals(1, result.poms());
        assertEquals(0, result.imported());
        assertEquals(List.of("skipping incomplete managed dependency org.example:no-version (declared in "
                + "io.quarkus:quarkus-bom:3.40.1)"), result.warnings());
    }

    @Test
    void readsPomsWithoutNamespace() throws IOException {
        String pom = "<project><dependencyManagement><dependencies><dependency><groupId>g</groupId>"
                + "<artifactId>a</artifactId><version>1</version></dependency></dependencies>"
                + "</dependencyManagement></project>";
        assertEquals(List.of(new ManagedDep("g:a", "1")), resolve(BomResolver.PomSource.NONE, pom).deps());
    }

    @Test
    void inheritsFromParentsAndInterpolatesInTheChildsContext() throws IOException {
        Repo repo = new Repo()
                .add("org.acme:grand:1", project("org.acme:grand:1",
                        properties("lib.version=1.0", "other.version=2.0"),
                        managed(dep("org.lib:from-grand:${lib.version}"), dep("org.lib:overridden:0.1"))))
                .add("org.acme:parent:2", project(":parent:2", parent("org.acme:grand:1"),
                        properties("lib.version=1.1"),
                        managed(dep("org.lib:from-parent:${other.version}"),
                                dep("${project.groupId}:self-parent:${project.version}"))));
        String bom = project(":bom:", parent("org.acme:parent:2"),
                properties("other.version=2.1"),
                managed(dep("org.lib:overridden:${lib.version}"),
                        dep("org.acme:parent-version:${project.parent.version}"),
                        dep("org.acme:legacy:${pom.version}"),
                        dep("org.acme:unprefixed:${version}"),
                        dep("org.acme:artifact:${project.artifactId}"),
                        dep("org.acme:packaging:${project.packaging}")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of(
                "org.lib:from-grand", "1.1",
                "org.lib:overridden", "1.1",
                "org.lib:from-parent", "2.1",
                "org.acme:self-parent", "2",
                "org.acme:parent-version", "2",
                "org.acme:legacy", "2",
                "org.acme:unprefixed", "2",
                "org.acme:artifact", "bom",
                "org.acme:packaging", "jar"), versions(result));
        assertEquals("org.acme:bom:2", result.coordinates());
        assertEquals(List.of("org.acme:parent:2", "org.acme:grand:1"), result.parents());
        assertEquals(3, result.poms());
        BomResolver.Entry fromGrand = result.entries().stream()
                .filter(entry -> entry.id().equals("org.lib:from-grand")).findFirst().orElseThrow();
        assertEquals("org.acme:grand:1", fromGrand.origin());
        assertEquals(null, fromGrand.via());
    }

    @Test
    void resolvesNestedPropertyReferences() throws IOException {
        String bom = project("g:bom:1",
                properties("a=${b}-x", "b=${c}", "c=1.0", "d=${c}.${c}"),
                managed(dep("g:a:${a}"), dep("g:d:${d}"), dep("g:e:${project.version}.${c}")));
        assertEquals(Map.of("g:a", "1.0-x", "g:d", "1.0.1.0", "g:e", "1.1.0"),
                versions(resolve(BomResolver.PomSource.NONE, bom)));
    }

    @Test
    void failsOnAPropertyReferenceCycle() {
        String bom = project("g:bom:1", properties("a=${b}", "b=x${a}"), managed(dep("g:a:${a}")));
        IOException e = assertThrows(IOException.class, () -> resolve(BomResolver.PomSource.NONE, bom));
        assertTrue(e.getMessage().contains("cycle: a -> b -> a"), e.getMessage());
    }

    @Test
    void ownAndInheritedEntriesBeatImportsAndEarlierImportsBeatLaterOnes() throws IOException {
        Repo repo = new Repo()
                .add("org.acme:parent:1", project("org.acme:parent:1",
                        managed(dep("org.lib:y:2.0"), bomImport("org.imp:third:1"))))
                .add("org.imp:first:1", project("org.imp:first:1",
                        properties("w.version=1-first-own"),
                        managed(dep("org.lib:x:9"), dep("org.lib:y:9"), dep("org.lib:z:1-first"),
                                dep("org.lib:w:${w.version}"))))
                .add("org.imp:second:1", project("org.imp:second:1",
                        managed(dep("org.lib:z:1-second"), dep("org.lib:v:1-second"))))
                .add("org.imp:third:1", project("org.imp:third:1",
                        managed(dep("org.lib:z:1-third"), dep("org.lib:v:1-third"), dep("org.lib:u:1-third"))));
        String bom = project("org.acme:bom:1", parent("org.acme:parent:1"),
                properties("w.version=from-the-importer"),
                managed(dep("org.lib:x:1.0"), bomImport("org.imp:first:1"), bomImport("org.imp:second:1")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of(
                "org.lib:x", "1.0",
                "org.lib:y", "2.0",
                "org.lib:z", "1-first",
                "org.lib:w", "1-first-own",
                "org.lib:v", "1-second",
                "org.lib:u", "1-third"), versions(result));
        assertEquals(List.of("org.imp:first:1", "org.imp:second:1", "org.imp:third:1"), result.imports());
        assertEquals(4, result.imported());
        BomResolver.Entry v = result.entries().stream().filter(entry -> entry.id().equals("org.lib:v"))
                .findFirst().orElseThrow();
        assertEquals("org.imp:second:1", v.origin());
        assertEquals("org.imp:second:1", v.via());
    }

    @Test
    void resolvesImportsOfImportsWithTheirOwnParents() throws IOException {
        Repo repo = new Repo()
                .add("org.imp:outer-parent:1", project("org.imp:outer-parent:1",
                        properties("inner.version=2"),
                        managed(dep("org.lib:from-outer-parent:1"))))
                .add("org.imp:outer:1", project(":outer:", parent("org.imp:outer-parent:1"),
                        managed(bomImport("org.imp:inner:${inner.version}"))))
                .add("org.imp:inner:2", project("org.imp:inner:2",
                        managed(dep("org.lib:deep:${project.version}"))));
        String bom = project("org.acme:bom:7", properties("inner.version=7"),
                managed(bomImport("org.imp:outer:1")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of("org.lib:from-outer-parent", "1", "org.lib:deep", "2"), versions(result));
        assertEquals(List.of("org.imp:outer:1", "org.imp:inner:2"), result.imports());
        assertEquals(4, result.poms());
        BomResolver.Entry deep = result.entries().stream().filter(entry -> entry.id().equals("org.lib:deep"))
                .findFirst().orElseThrow();
        assertEquals("org.imp:inner:2", deep.origin());
        assertEquals("org.imp:outer:1", deep.via());
    }

    @Test
    void theManagementKeyIncludesTypeAndClassifier() throws IOException {
        Repo repo = new Repo().add("org.imp:bom:1", project("org.imp:bom:1",
                managed(dep("g:a:2"), dep("g:a:test-jar:tests:2"), dep("g:a::linux:3"), dep("g:a:jar::4"))));
        String bom = project("g:bom:1", managed(dep("g:a:1"), bomImport("org.imp:bom:1")));
        assertEquals(Map.of("g:a", "1", "g:a:test-jar:tests", "2", "g:a:linux", "3"), versions(resolve(repo, bom)));
    }

    @Test
    void mergesDuplicatesOfOnePomAsMavenDoes() throws IOException {
        String alone = project("g:bom:1", managed(dep("g:a:1"), dep("g:a:2")));
        BomResolver.Result first = resolve(BomResolver.PomSource.NONE, alone);
        assertEquals(Map.of("g:a", "1"), versions(first));
        assertEquals(List.of("g:a managed twice (1, 2); keeping the first"), first.warnings());

        Repo repo = new Repo().add("org.imp:bom:1", project("org.imp:bom:1", managed(dep("g:b:1"))));
        String merged = project("g:bom:1", managed(dep("g:a:1"), dep("g:a:2"), bomImport("org.imp:bom:1")));
        assertEquals(Map.of("g:a", "2", "g:b", "1"), versions(resolve(repo, merged)),
                "merging imports keeps the last of the pom's own entries with one key");
    }

    @Test
    void failsOnAnImportCycle() {
        Repo repo = new Repo()
                .add("org.imp:a:1", project("org.imp:a:1", managed(bomImport("org.imp:b:1"))))
                .add("org.imp:b:1", project("org.imp:b:1", managed(bomImport("org.imp:a:1"))));
        String bom = project("g:bom:1", managed(bomImport("org.imp:a:1")));
        IOException e = assertThrows(IOException.class, () -> resolve(repo, bom));
        assertEquals("the BOM imports form a cycle: g:bom:1 -> org.imp:a:1 -> org.imp:b:1 -> org.imp:a:1",
                e.getMessage());

        String self = project("g:bom:1", managed(bomImport("g:bom:1")));
        assertThrows(IOException.class, () -> resolve(new Repo().add("g:bom:1", self), self));
    }

    @Test
    void failsOnAParentCycle() {
        Repo repo = new Repo()
                .add("g:p:1", project("g:p:1", parent("g:q:1")))
                .add("g:q:1", project("g:q:1", parent("g:p:1")));
        String bom = project("g:bom:1", parent("g:p:1"));
        IOException e = assertThrows(IOException.class, () -> resolve(repo, bom));
        assertEquals("the parents form a cycle: g:bom:1 -> g:p:1 -> g:q:1 -> g:p:1", e.getMessage());
    }

    @Test
    void resolvesLeftoverPropertiesOfAFlattenedBomFromItsPropertiesAndParents() throws IOException {
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1", properties("boring.version=2.0.46")));
        String bom = project("io.quarkus:quarkus-bom:2.4.1.Final", parent("g:parent:1"),
                properties("tcnative.version=2.0.45"),
                managed(dep("io.netty:netty-tcnative::linux-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative-boringssl-static:${boring.version}")));
        BomResolver.Result result = resolve(repo, bom).requireResolved();
        assertEquals(Map.of("io.netty:netty-tcnative:linux-x86_64", "2.0.45",
                "io.netty:netty-tcnative-boringssl-static", "2.0.46"), versions(result));
    }

    @Test
    void anUnresolvedVersionFailsTheBom() throws IOException {
        String bom = project("io.quarkus:quarkus-bom:2.4.1.Final",
                managed(dep("io.netty:netty-tcnative:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::linux-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::osx-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::windows-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::linux-aarch_64:${tcnative.version}"),
                        dep("${unknown.group}:art:1"),
                        dep("io.netty:netty-common:4.1.69.Final")));

        BomResolver.Result result = resolve(BomResolver.PomSource.NONE, bom);

        assertEquals(6, result.unresolved().size());
        assertEquals(List.of("${tcnative.version}"), result.unresolved().getLast().unresolved());
        IOException e = assertThrows(IOException.class, result::requireResolved);
        assertEquals("6 managed dependencies keep unresolved references: ${tcnative.version} (declared in "
                + "io.quarkus:quarkus-bom:2.4.1.Final): io.netty:netty-tcnative, "
                + "io.netty:netty-tcnative:linux-aarch_64, io.netty:netty-tcnative:linux-x86_64 and 2 more; "
                + "${unknown.group} (declared in "
                + "io.quarkus:quarkus-bom:2.4.1.Final): ${unknown.group}:art; neither the poms that declare them nor "
                + "their parents define these properties (profiles that depend on the machine are not applied)",
                e.getMessage());
    }

    @Test
    void anOverriddenUnresolvedImportEntryDoesNotMatter() throws IOException {
        Repo repo = new Repo().add("io.netty:netty-bom:4.1.69.Final", project("io.netty:netty-bom:4.1.69.Final",
                managed(dep("io.netty:netty-tcnative:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::linux-x86_64:${tcnative.version}"))));
        String bom = project("g:bom:1", managed(dep("io.netty:netty-tcnative:2.0.44.Final"),
                bomImport("io.netty:netty-bom:4.1.69.Final")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(List.of("io.netty:netty-tcnative:linux-x86_64"),
                result.unresolved().stream().map(BomResolver.Entry::id).toList());
        IOException e = assertThrows(IOException.class, result::requireResolved);
        assertTrue(e.getMessage().startsWith("1 managed dependency keeps unresolved references: ${tcnative.version} "
                + "(declared in io.netty:netty-bom:4.1.69.Final, imported through io.netty:netty-bom:4.1.69.Final): "
                + "io.netty:netty-tcnative:linux-x86_64;"), e.getMessage());
    }

    @Test
    void failsOnImportsAndParentsItCannotRead() {
        Repo repo = new Repo();
        IOException missingImport = assertThrows(IOException.class,
                () -> resolve(repo, project("g:bom:1", managed(bomImport("org.imp:gone:1")))));
        assertEquals("the BOM org.imp:gone:1 imported by g:bom:1 is not in the repository", missingImport.getMessage());

        IOException unresolvedImport = assertThrows(IOException.class,
                () -> resolve(repo, project("g:bom:1", managed(bomImport("org.imp:bom:${bom.version}")))));
        assertTrue(unresolvedImport.getMessage().contains("org.imp:bom:${bom.version}"), unresolvedImport.getMessage());

        IOException missingParent = assertThrows(IOException.class,
                () -> resolve(repo, project(":bom:", parent("g:gone:1"))));
        assertEquals("the parent g:gone:1 of g:bom:1 is not in the repository", missingParent.getMessage());
    }

    /// A `<profile>` with the given activation (empty for none) and other elements.
    private static String profile(String id, String activation, String... elements) {
        return "<profile><id>" + id + "</id>" + (activation.isEmpty() ? "" : "<activation>" + activation
                + "</activation>") + String.join("", elements) + "</profile>";
    }

    @Test
    void appliesTheProfilesThatAPlainBuildActivatesOnEveryMachine() throws IOException {
        String bom = project("g:bom:1",
                properties("x.version=1", "y.version=1"),
                managed(dep("g:x:${x.version}"), dep("g:y:${y.version}"), dep("g:replaced:1")),
                "<profiles>"
                        + profile("without-skip", "<property><name>!skip.extra</name></property>",
                                properties("x.version=2"), managed(dep("g:extra:1"), dep("g:replaced:2")))
                        + profile("with-enable", "<property><name>enable</name></property>",
                                managed(dep("g:never:1")))
                        + profile("not-fast", "<property><name>mode</name><value>!fast</value></property>",
                                managed(dep("g:mode:1")))
                        + profile("fast", "<property><name>mode</name><value>fast</value></property>",
                                managed(dep("g:fast:1")))
                        + profile("default", "<activeByDefault>true</activeByDefault>", properties("y.version=2"))
                        + profile("manual", "", managed(dep("g:manual:1")))
                        + profile("jdk11", "<jdk>[11,)</jdk>", managed(dep("g:jdk:1")), properties("y.version=11"))
                        + profile("java-vendor", "<property><name>!java.vendor</name></property>",
                                managed(dep("g:vendor:1")))
                        + "</profiles>");

        BomResolver.Result result = resolve(BomResolver.PomSource.NONE, bom);

        assertEquals(Map.of("g:x", "2", "g:y", "1", "g:replaced", "2", "g:extra", "1", "g:mode", "1"),
                versions(result));
        assertEquals(List.of("without-skip of g:bom:1", "not-fast of g:bom:1"), result.profiles());
        assertEquals(List.of(
                "profiles that depend on the machine are not applied: profile jdk11 of g:bom:1 (JDK [11,)) manages 1 "
                        + "dependency",
                "profiles that depend on the machine are not applied: profile jdk11 of g:bom:1 (JDK [11,)) sets the "
                        + "property y.version, which the managed dependencies of g:bom:1 use",
                "profiles that depend on the machine are not applied: profile java-vendor of g:bom:1 (property "
                        + "!java.vendor) manages 1 dependency"), result.warnings());
    }

    @Test
    void activeByDefaultProfilesApplyWhenNoOtherProfileOfThePomIsActive() throws IOException {
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1",
                properties("a.version=1"),
                "<profiles>" + profile("default", "<activeByDefault>true</activeByDefault>", properties("a.version=2"),
                        managed(dep("g:from-parent-profile:1"))) + profile("other", "<property><name>other</name>"
                        + "</property>", properties("a.version=3")) + "</profiles>"));
        String bom = project(":bom:", parent("g:parent:1"), managed(dep("g:a:${a.version}")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of("g:a", "2", "g:from-parent-profile", "1"), versions(result));
        assertEquals(List.of("default of g:parent:1"), result.profiles());
    }

    @Test
    void aProfileOfAnImportedBomsParentCanImportAnotherBom() throws IOException {
        // shaped like resteasy-bom 4.0.0.Beta8, whose parent imports resteasy-dependencies unless
        // -Dresteasy.dependencies.eap is given
        Repo repo = new Repo()
                .add("org.jboss.resteasy:resteasy-jaxrs-all:4.0.0.Beta8", project(
                        "org.jboss.resteasy:resteasy-jaxrs-all:4.0.0.Beta8",
                        "<profiles>"
                                + profile("resteasy-default", "<property><name>!resteasy.dependencies.eap</name>"
                                + "</property>", managed(bomImport("org.jboss.resteasy:resteasy-dependencies:"
                                + "${project.version}")))
                                + profile("resteasy-dependencies-eap", "<property><name>resteasy.dependencies.eap"
                                + "</name></property>", managed(bomImport("org.jboss.resteasy:eap:1")))
                                + "</profiles>"))
                .add("org.jboss.resteasy:resteasy-bom:4.0.0.Beta8", project(":resteasy-bom:",
                        parent("org.jboss.resteasy:resteasy-jaxrs-all:4.0.0.Beta8"),
                        managed(dep("org.jboss.resteasy:resteasy-core:${project.version}"))))
                .add("org.jboss.resteasy:resteasy-dependencies:4.0.0.Beta8", project(
                        "org.jboss.resteasy:resteasy-dependencies:4.0.0.Beta8",
                        managed(dep("org.jboss.resteasy:resteasy-core:0-loses"), dep("com.google.inject:guice:4.2"))));
        String bom = project("io.quarkus:quarkus-bom:0.11.0",
                managed(bomImport("org.jboss.resteasy:resteasy-bom:4.0.0.Beta8")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of("org.jboss.resteasy:resteasy-core", "4.0.0.Beta8", "com.google.inject:guice", "4.2"),
                versions(result));
        assertEquals(List.of("org.jboss.resteasy:resteasy-bom:4.0.0.Beta8",
                "org.jboss.resteasy:resteasy-dependencies:4.0.0.Beta8"), result.imports());
        assertEquals(List.of("resteasy-default of org.jboss.resteasy:resteasy-jaxrs-all:4.0.0.Beta8"),
                result.profiles());
        assertEquals(0, repo.reads("org.jboss.resteasy:eap:1"));
    }

    @Test
    void comparesKeysAsWrittenWhenInheritingAndInterpolatedWhenImporting() throws IOException {
        // the child's ${project.groupId}:a and the parent's g:a are two entries until interpolation; merging the
        // imports then keeps the last of them, the parent's, while a pom without imports keeps both, the first
        // counting
        Repo repo = new Repo()
                .add("g:parent:1", project("g:parent:1", managed(dep("g:a:from-parent"))))
                .add("org.imp:i:1", project("org.imp:i:1", managed(dep("g:b:1"))));

        assertEquals(Map.of("g:a", "from-child"), versions(resolve(repo, project(":bom:", parent("g:parent:1"),
                managed(dep("${project.groupId}:a:from-child"))))));
        assertEquals(Map.of("g:a", "from-parent", "g:b", "1"), versions(resolve(repo, project(":bom:",
                parent("g:parent:1"), managed(dep("${project.groupId}:a:from-child"), bomImport("org.imp:i:1"))))));
    }

    @Test
    void anExplicitJarTypeIsTheDefaultKeyButATestJarAndATestsClassifierAreNot() throws IOException {
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1", managed(dep("g:a:parent"),
                dep("g:t:test-jar:tests:parent-tests"), dep("g:t::tests:parent-jar-tests"),
                dep("g:t:test-jar::parent-test-jar"))));
        String bom = project(":bom:", parent("g:parent:1"),
                managed(dep("g:a:jar::child"), dep("g:t:test-jar::child-test-jar")));

        assertEquals(Map.of("g:a", "child", "g:t:test-jar", "child-test-jar", "g:t:test-jar:tests", "parent-tests",
                "g:t:tests", "parent-jar-tests"), versions(resolve(repo, bom)));
    }

    @Test
    void importsInTheOrderOfTheMergedModelOwnThenProfileThenParent() throws IOException {
        Repo repo = new Repo()
                .add("g:parent:1", project("g:parent:1", managed(bomImport("org.imp:c:1"))))
                .add("org.imp:a:1", project("org.imp:a:1", managed(dep("x:ab:a"), dep("x:ac:a"))))
                .add("org.imp:b:1", project("org.imp:b:1", managed(dep("x:ab:b"), dep("x:bc:b"))))
                .add("org.imp:c:1", project("org.imp:c:1", managed(dep("x:ac:c"), dep("x:bc:c"), dep("x:c:c"))));
        String bom = project(":bom:", parent("g:parent:1"), managed(bomImport("org.imp:a:1")), "<profiles>"
                + profile("on", "<property><name>!off</name></property>", managed(bomImport("org.imp:b:1")))
                + "</profiles>");

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of("x:ab", "a", "x:ac", "a", "x:bc", "b", "x:c", "c"), versions(result));
        assertEquals(List.of("org.imp:a:1", "org.imp:b:1", "org.imp:c:1"), result.imports());
    }

    @Test
    void anImportedBomIsCompleteWithItsOwnImportsBeforeTheNextImport() throws IOException {
        Repo repo = new Repo()
                .add("org.imp:first:1", project("org.imp:first:1",
                        managed(bomImport("org.imp:nested:1"), dep("x:own:first-own"))))
                .add("org.imp:nested:1", project("org.imp:nested:1",
                        managed(dep("x:deep:nested"), dep("x:own:nested"))))
                .add("org.imp:second:1", project("org.imp:second:1", managed(dep("x:deep:second"), dep("x:s:second"))));
        String bom = project("g:bom:1", managed(bomImport("org.imp:first:1"), bomImport("org.imp:second:1")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of("x:deep", "nested", "x:own", "first-own", "x:s", "second"), versions(result),
                "a nested import of the first import beats the second import, and loses to its importer's own entry");
        assertEquals(List.of("org.imp:first:1", "org.imp:nested:1", "org.imp:second:1"), result.imports());
    }

    @Test
    void anImportedBomIsBuiltInItsOwnContextWithItsParents() throws IOException {
        Repo repo = new Repo()
                .add("org.imp:child:7", project(":child:7", parent("org.imp:imp-parent:3")))
                .add("org.imp:imp-parent:3", project(":imp-parent:3", parent("org.imp:imp-grand:2"),
                        managed(dep("x:pv:${project.version}"), dep("x:parentv:${project.parent.version}"),
                                dep("x:lib:${lib.version}"), dep("${project.groupId}:${project.artifactId}-x:1"))))
                .add("org.imp:imp-grand:2", project("org.imp:imp-grand:2",
                        properties("lib.version=${chain.a}", "chain.a=${chain.b}-a",
                                "chain.b=grand-${project.artifactId}")));
        String bom = project("g:bom:1", properties("lib.version=importer"), managed(bomImport("org.imp:child:7")));

        assertEquals(Map.of("x:pv", "7", "x:parentv", "3", "x:lib", "grand-child-a", "org.imp:child-x", "1"),
                versions(resolve(repo, bom)), "the importer's properties do not reach the imported BOM");
    }

    @Test
    void activeByDefaultIsDecidedForEachPomOfTheLineage() throws IOException {
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1", properties("q=parent", "p=parent"),
                "<profiles>" + profile("default", "<activeByDefault>true</activeByDefault>",
                        properties("q=parent-default"), managed(dep("x:pd:parent-default"))) + "</profiles>"));
        String bom = project(":bom:", parent("g:parent:1"), properties("p=own"),
                managed(dep("x:p:${p}"), dep("x:q:${q}")), "<profiles>"
                        + profile("default", "<activeByDefault>true</activeByDefault>", properties("p=child-default"))
                        + profile("on", "<property><name>!off</name></property>", properties("q=child-on"))
                        + "</profiles>");

        assertEquals(Map.of("x:p", "own", "x:pd", "parent-default", "x:q", "child-on"), versions(resolve(repo, bom)));
    }

    @Test
    void anImportedBomKeepsTheFirstOfItsDuplicates() throws IOException {
        Repo repo = new Repo().add("org.imp:dups:1", project("org.imp:dups:1",
                managed(dep("x:imp:first"), dep("x:imp:last"))));
        String bom = project("g:bom:1", managed(dep("x:own:first"), dep("x:own:last"), bomImport("org.imp:dups:1")));

        assertEquals(Map.of("x:imp", "first", "x:own", "last"), versions(resolve(repo, bom)));
    }

    @Test
    void aProfileEntryReplacesThePomsOwnEntryInPlace() throws IOException {
        Repo repo = new Repo().add("org.imp:i:1", project("org.imp:i:1", managed(dep("x:y:import"))));
        String bom = project("g:bom:1", managed(dep("x:y:own"), bomImport("org.imp:i:1")), "<profiles>"
                + profile("on", "<property><name>flag</name><value>!x</value></property>", managed(dep("x:y:profile")))
                + "</profiles>");

        assertEquals(Map.of("x:y", "profile"), versions(resolve(repo, bom)));
    }

    @Test
    void importsOnlyPomTypeEntriesOfImportScopeWhateverTheirClassifier() throws IOException {
        Repo repo = new Repo().add("org.imp:j:1", project("org.imp:j:1", managed(dep("x:from-j:1"))));
        String bom = project("g:bom:1", managed(
                "<dependency><groupId>org.imp</groupId><artifactId>i</artifactId><version>1</version>"
                        + "<type>pom</type><scope>test</scope></dependency>",
                "<dependency><groupId>org.imp</groupId><artifactId>j</artifactId><version>1</version>"
                        + "<type>pom</type><classifier>c</classifier><scope>import</scope></dependency>"));

        assertEquals(Map.of("org.imp:i:pom", "1", "x:from-j", "1"), versions(resolve(repo, bom)));
        assertEquals(0, repo.reads("org.imp:i:1"));
    }

    @Test
    void anImportVersionCanComeFromAPropertyOnlyTheGrandparentDefines() throws IOException {
        Repo repo = new Repo()
                .add("g:parent:1", project(":parent:", parent("g:grand:1")))
                .add("g:grand:1", project("g:grand:1", properties("imp.version=2")))
                .add("org.imp:i:2", project("org.imp:i:2", managed(dep("x:from-i:${project.version}"))));
        String bom = project(":bom:", parent("g:parent:1"), managed(bomImport("org.imp:i:${imp.version}")));

        BomResolver.Result result = resolve(repo, bom);

        assertEquals(Map.of("x:from-i", "2"), versions(result));
        assertEquals("g:bom:1", result.coordinates());
        assertEquals(List.of("g:parent:1", "g:grand:1"), result.parents());
    }

    @Test
    void followsAParentByItsCoordinatesNotByItsRelativePath() throws IOException {
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1", properties("from.parent=repository")));
        String bom = project(":bom:", "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version>"
                + "<relativePath>../../pom.xml</relativePath></parent>", managed(dep("x:y:${from.parent}")));

        assertEquals(Map.of("x:y", "repository"), versions(resolve(repo, bom)));
    }

    @Test
    void failsOnAnImportCycleThroughAParentsImport() {
        Repo repo = new Repo()
                .add("org.imp:a:1", project(":a:", parent("org.imp:a-parent:1")))
                .add("org.imp:a-parent:1", project("org.imp:a-parent:1", managed(bomImport("g:bom:1"))))
                .add("g:bom:1", project("g:bom:1", managed(bomImport("org.imp:a:1"))));
        IOException e = assertThrows(IOException.class,
                () -> resolve(repo, project("g:bom:1", managed(bomImport("org.imp:a:1")))));
        assertEquals("the BOM imports form a cycle: g:bom:1 -> org.imp:a:1 -> g:bom:1", e.getMessage());
    }

    @Test
    void failsWhenTheParentOfAnImportedBomIsMissing() {
        Repo repo = new Repo().add("org.imp:a:1", project(":a:", parent("org.imp:gone:1")));
        IOException e = assertThrows(IOException.class,
                () -> resolve(repo, project("g:bom:1", managed(bomImport("org.imp:a:1"), dep("x:y:1")))));
        assertEquals("the parent org.imp:gone:1 of org.imp:a:1 is not in the repository", e.getMessage());
    }

    @Test
    void aReferenceThatIsNeverClosedIsUnresolved() throws IOException {
        BomResolver.Result result = resolve(BomResolver.PomSource.NONE,
                project("g:bom:1", managed(dep("x:y:1.0-${oops"), dep("x:z:1"))));
        assertEquals(List.of("${oops"), result.unresolved().getFirst().unresolved());
        assertThrows(IOException.class, result::requireResolved);
    }

    @Test
    void refusesParentsAndImportsWhoseCoordinatesWouldLeaveTheRepositoryFolder() {
        Repo repo = new Repo();
        for (String coordinates : List.of("g:..:..", "g:a:../../x", "g:a:..\\..\\x", "g:a b:1", "g:a:1 2", "g:a:c:d")) {
            String[] parts = coordinates.split(":", 3);
            String parentElement = "<parent><groupId>" + parts[0] + "</groupId><artifactId>" + parts[1]
                    + "</artifactId><version>" + parts[2] + "</version></parent>";
            IOException parent = assertThrows(IOException.class,
                    () -> resolve(repo, project("g:bom:1", parentElement)), coordinates);
            assertTrue(parent.getMessage().contains("no valid Maven coordinates"), parent.getMessage());
            String importElement = "<dependency><groupId>" + parts[0] + "</groupId><artifactId>" + parts[1]
                    + "</artifactId><version>" + parts[2] + "</version><type>pom</type><scope>import</scope>"
                    + "</dependency>";
            IOException imported = assertThrows(IOException.class,
                    () -> resolve(repo, project("g:bom:1", managed(importElement))), coordinates);
            assertTrue(imported.getMessage().contains("no valid Maven coordinates"), imported.getMessage());
        }
        assertEquals(Map.of(), repo.reads);
    }

    @Test
    void readsEachPomOnceAcrossBomsAndThreads() throws Exception {
        Repo repo = new Repo()
                .add("g:parent:1", project("g:parent:1", properties("x.version=1"), managed(dep("g:p:1"))))
                .add("org.imp:bom:1", project("org.imp:bom:1", parent("g:parent:1"), managed(dep("g:i:1"))));
        BomResolver resolver = new BomResolver(repo);
        List<String> boms = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            boms.add(project("g:bom:" + i, parent("g:parent:1"),
                    managed(dep("g:own:${x.version}"), bomImport("org.imp:bom:1"))));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BomResolver.Result>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (String bom : boms) {
                results.add(executor.submit(() -> {
                    start.await();
                    return resolver.resolve(bom.getBytes(StandardCharsets.UTF_8), "1");
                }));
            }
            start.countDown();
            for (Future<BomResolver.Result> result : results) {
                assertEquals(Map.of("g:own", "1", "g:p", "1", "g:i", "1"), versions(result.get()));
            }
        }
        assertEquals(1, repo.reads("g:parent:1"));
        assertEquals(1, repo.reads("org.imp:bom:1"));
    }
}
