package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Parallel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void failsOnAPropertyCycleThatNoManagedDependencyReadsAsMavenDoes() {
        // Maven interpolates every property of the model: an unused cycle of the BOM, of a parent, of an active
        // profile or of an imported BOM fails the build
        Repo repo = new Repo()
                .add("g:parent:1", project("g:parent:1", properties("a=pre-${a}")))
                .add("org.imp:i:1", project("org.imp:i:1", properties("p=${q}", "q=${p}"), managed(dep("g:y:2"))));
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("g:bom:1", properties("a=${a}"), managed(dep("g:x:${unknown}"))),
                "g:bom:1: the property references form a cycle: a -> a");
        failures.put(project(":bom:", parent("g:parent:1"), managed(dep("g:x:1"))),
                "g:bom:1: the property references form a cycle: a -> a");
        failures.put(project("g:bom:1", managed(dep("g:x:1")), "<profiles>" + profile("on",
                        "<property><name>!skip</name></property>", properties("a=x-${a}")) + "</profiles>"),
                "g:bom:1: the property references form a cycle: a -> a");
        // as on a machine that does not activate the profile
        failures.put(project("g:bom:1", properties("a=${a}"), managed(dep("g:x:1")),
                        "<profiles>" + profile("jdk11", "<jdk>[11,)</jdk>", properties("a=1")) + "</profiles>"),
                "g:bom:1: the property references form a cycle: a -> a");
        failures.put(project("g:bom:1", managed(dep("g:x:${unknown}"), bomImport("org.imp:i:1"))),
                "org.imp:i:1: the property references form a cycle: q -> p -> q");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));
    }

    @Test
    void aValueThatHoldsTheReferenceItselfIsPassedOverAsMavenDoes() throws IOException {
        // the next value is the model's version here, and one that Maven may find in the model or on the machine for
        // name and java.home; a cycle that the child overrides is no cycle either; the result may read a bare
        // reference to itself, which gives the next value in Maven too
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1", properties("a=pre-${a}")));
        String bom = project(":bom:", parent("g:parent:1"),
                properties("a=plain", "version=x-${version}", "name=${name}", "java.home=${java.home}",
                        "v2=${project.version}", "artifactId=${artifactId}"),
                managed(dep("g:v:${project.version}"), dep("g:w:${v2}"), dep("g:x:${artifactId}")));
        assertEquals(Map.of("g:v", "1", "g:w", "1", "g:x", "bom"), versions(resolve(repo, bom)));
        assertEquals(Map.of("g:a", "1"), versions(resolve(BomResolver.PomSource.NONE,
                project("g:bom:1", properties("version=${version}"), managed(dep("g:a:${version}"))))));
    }

    @Test
    void aPropertyThatTheResultReadsAndThatRefersToItselfWithMoreTextFails() {
        // Maven interpolates every property in place before it reads the managed dependencies and the coordinates, in
        // an order that depends on the property names: version=x-${version} gives x-1 there, or 1 when it passes the
        // property over, also through another property (a=${version} gives 1, n=${version} gives x-1)
        Repo repo = new Repo()
                .add("t:imp:5", project("t:imp:5", properties("version=${version}.Final"),
                        managed(dep("g:b:${version}"))))
                .add("t:p:7", project("t:p:7"));
        String tail = ", which refers to itself with more text: Maven interpolates such a property in place, in an "
                + "order that depends on the property names, so the value of ";
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("t:bom:1", properties("version=x-${version}"), managed(dep("g:a:${version}"))),
                "t:bom:1: ${version} reads the property version, x-${version}" + tail + "${version} is not known");
        failures.put(project("t:bom:1", managed(bomImport("t:imp:5"))),
                "t:imp:5: ${version} reads the property version, ${version}.Final" + tail + "${version} is not known");
        failures.put(project("t:bom:5", properties("version=${version}.0"), managed(bomImport("t:imp:${version}"))),
                "t:bom:5: ${version} reads the property version, ${version}.0" + tail + "${version} is not known");
        failures.put(project("t:bom:1", properties("artifactId=${artifactId}-x"), managed(dep("g:a:${artifactId}"))),
                "t:bom:1: ${artifactId} reads the property artifactId, ${artifactId}-x" + tail
                        + "${artifactId} is not known");
        failures.put(project(":bom:", parent("t:p:7"), properties("parent.version=${parent.version}-x"),
                        managed(dep("g:a:${parent.version}"))),
                "t:bom:7: ${parent.version} reads the property parent.version, ${parent.version}-x" + tail
                        + "${parent.version} is not known");
        for (String reader : List.of("a", "n", "o", "zz", "vx", "netty.version")) {
            failures.put(project("t:bom:1", properties(reader + "=${version}", "version=x-${version}"),
                            managed(dep("g:a:${" + reader + "}"))),
                    "t:bom:1: ${version} reads the property version, x-${version}" + tail + "${version} is not known");
        }
        // a groupId, a classifier, a scope and the coordinates of the BOM read it too
        failures.put(project("t:bom:1", properties("groupId=${groupId}.x"), managed(dep("${groupId}:a:1"))),
                "t:bom:1: ${groupId} reads the property groupId, ${groupId}.x" + tail + "${groupId} is not known");
        failures.put(project("t:bom:1", properties("c=${c}-x"), managed(dep("g:a::${c}:1"))),
                "t:bom:1: ${c} reads the property c, ${c}-x" + tail + "${c} is not known");
        failures.put(project("t:bom:1", properties("s=x${s}"), managed("<dependency><groupId>g</groupId>"
                        + "<artifactId>a</artifactId><version>1</version><scope>${s}</scope></dependency>")),
                "t:bom:1: ${s} reads the property s, x${s}" + tail + "${s} is not known");
        failures.put(project("g-${version}:bom:1", properties("version=x-${version}"), managed(dep("g:a:1"))),
                "g-${version}:bom:1: ${version} reads the property version, x-${version}" + tail
                        + "${version} is not known");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));
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
        BomResolver.Result result = resolve(repo, bom);
        assertEquals(Map.of("io.netty:netty-tcnative:linux-x86_64", "2.0.45",
                "io.netty:netty-tcnative-boringssl-static", "2.0.46"), versions(result));
        assertEquals(List.of(), result.unresolved());
        assertEquals(Optional.empty(), result.describeUnresolved());
    }

    @Test
    void anUnresolvedVersionStaysAsWrittenAndIsDescribed() throws IOException {
        String bom = project("io.quarkus:quarkus-bom:2.4.1.Final",
                managed(dep("io.netty:netty-tcnative:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::linux-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::osx-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::windows-x86_64:${tcnative.version}"),
                        dep("io.netty:netty-tcnative::linux-aarch_64:${tcnative.version}"),
                        dep("org.acme:art:${art.type}::1"),
                        dep("io.netty:netty-common:4.1.69.Final")));

        BomResolver.Result result = resolve(BomResolver.PomSource.NONE, bom);

        assertEquals("${tcnative.version}", versions(result).get("io.netty:netty-tcnative:linux-x86_64"));
        assertEquals("1", versions(result).get("org.acme:art:${art.type}"), "a type is kept as written too");
        assertEquals(7, result.deps().size());
        assertEquals(6, result.unresolved().size());
        assertEquals(List.of("${tcnative.version}"), result.unresolved().getFirst().unresolved());
        assertEquals(Optional.of("6 managed dependencies keep unresolved references: ${art.type} (declared in "
                + "io.quarkus:quarkus-bom:2.4.1.Final): org.acme:art:${art.type}; ${tcnative.version} (declared in "
                + "io.quarkus:quarkus-bom:2.4.1.Final): io.netty:netty-tcnative, "
                + "io.netty:netty-tcnative:linux-aarch_64, io.netty:netty-tcnative:linux-x86_64 and 2 more; "
                + "neither the poms that declare them nor their parents define these properties"),
                result.describeUnresolved());
    }

    @Test
    void keepsAsWrittenTheReferencesThatMavenKeepsOnEveryMachine() throws IOException {
        // Maven reads a text element of the model only as a whole or through a getter of its String, and a project or
        // parent element that is not there gives nothing; without project directory it knows no basedir either
        Repo repo = new Repo().add("org.imp:i:1", project("org.imp:i:1",
                managed(dep("g:a:${java.version}"), dep("g:b:2"))));
        String bom = project("io.quarkus:quarkus-bom:1",
                "<name>The BOM</name>",
                managed(dep("org.jboss:jdk-misc:${version.jdk-misc}"), dep("g:name-x:${name.x}"),
                        dep("g:version-jdk:${project.version.jdk}"), dep("g:class-name:${version.class-name}"),
                        dep("org.apache.maven.shared:maven-artifact-transfer:${maven-artifact-transfer.version}"),
                        dep("org.glassfish.web:el-impl:${el-impl.version}"), dep("g:basedir:${project.basedir}"),
                        dep("g:basedir2:${basedir}"), dep("g:foo:${project.foo}"), dep("g:parent:${parent.version}"),
                        dep("g:project-parent:${project.parent.version}"), dep("g:a:1.0"),
                        bomImport("org.imp:i:1")),
                "<profiles>" + profile("jdk11", "<jdk>[11,)</jdk>", properties("other.version=11")) + "</profiles>");

        BomResolver.Result result = resolve(repo, bom);

        assertEquals("${version.jdk-misc}", versions(result).get("org.jboss:jdk-misc"));
        assertEquals("${version.class-name}", versions(result).get("g:class-name"));
        assertEquals("${parent.version}", versions(result).get("g:parent"));
        assertEquals("1.0", versions(result).get("g:a"), "the importer manages it: the import's machine value is out");
        assertEquals(11, result.unresolved().size());
    }

    @Test
    void failsOnAReferenceThatMavenMayResolveWhereTheResolutionDoesNotLook() {
        // Maven reads more of the model than the coordinates, the packaging and the parent, and takes properties from
        // the machine that builds
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1"));
        String model = ", which Maven may read from the model of g:bom:1, beyond the coordinates, packaging, parent "
                + "and properties that the resolution reads";
        String machine = ", which Maven may take from the machine that builds: a property of the JVM, of the "
                + "environment or of Maven itself";
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("g:bom:1", "<name>The BOM</name>", managed(dep("g:a:${project.name}"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${project.name}" + model);
        failures.put(project("g:bom:1", managed(dep("g:a:${name}"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${name}" + model);
        failures.put(project("g:bom:1", managed(dep("g:a:${project.build.finalName}"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${project.build.finalName}" + model);
        failures.put(project("g:bom:1", properties("n=${pom.description}"), managed(dep("g:a:x-${n}"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${pom.description}" + model);
        failures.put(project("g:bom:1", parent("g:parent:1"), managed(dep("g:a:${project.parent.relativePath}"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${project.parent.relativePath}" + model);
        // what Java's reflection gives: Object.getClass() of the model, the getters of a text element's String
        for (String reference : List.of("${class}", "${project.class.simpleName}", "${version.empty}",
                "${pom.name.bytes}", "${groupId.blank}", "${packaging.class}", "${artifactId.class.simpleName}")) {
            failures.put(project("g:bom:1", "<name>N</name>", managed(dep("g:a:" + reference))),
                    "the managed dependency g:a (declared in g:bom:1) keeps " + reference + model);
        }
        failures.put(project("g:bom:1", managed(dep("g:a:${java.version}"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${java.version}" + machine);
        failures.put(project("g:bom:1", managed(dep("g:a:${env.HOME}"), dep("g:b::${user.home}:1"))),
                "the managed dependency g:a (declared in g:bom:1) keeps ${env.HOME}" + machine
                        + "; 1 more managed dependency keeps such a reference");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));
    }

    @Test
    void failsOnATypeOrClassifierThatKeepsThePlaceholderText() throws IOException {
        // an id writes ${quarkus.version} for a classifier equal to the release version
        for (String entry : List.of("g:a::linux-${quarkus.version}:2", "g:a:${quarkus.version}::2")) {
            IOException e = assertThrows(IOException.class,
                    () -> resolve(BomResolver.PomSource.NONE, project("g:bom:1", managed(dep(entry)))), entry);
            assertTrue(e.getMessage().startsWith("the managed dependency g:a (declared in g:bom:1) keeps "),
                    e.getMessage());
            assertTrue(e.getMessage().endsWith(" in its type or classifier, which an id could not tell from the "
                    + "placeholder ${quarkus.version} of a classifier equal to the release version"), e.getMessage());
        }
        // a property that gives it the release version leaves the placeholder of the id only
        assertEquals(Map.of("g:a:${quarkus.version}", "2"), versions(resolve(BomResolver.PomSource.NONE,
                project("g:bom:1", properties("quarkus.version=1"), managed(dep("g:a::${quarkus.version}:2"))))));
    }

    @Test
    void failsOnAManagedDependencyWithoutAValidGroupIdOrArtifactIdAsMavenDoes() {
        // Maven's validation of the effective model rejects them, also in an imported BOM and in a parent, and also
        // without a version; a reference that no pom defines is no valid id either
        Repo repo = new Repo()
                .add("org.imp:i:1", project("org.imp:i:1", managed(dep("${unknown.group}:x:1"), dep("g:y:1"))))
                .add("g:parent:1", project("g:parent:1", managed(dep("g:${unknown.artifact}:1"))));
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("g:bom:1", managed(dep("${unknown.group}:art:1"), dep("g:ok:1"))),
                "g:bom:1 manages ${unknown.group}:art, whose groupId ${unknown.group} is no valid Maven id (it keeps "
                        + "a reference that no pom defines)");
        failures.put(project("g:bom:1", managed(dep("g:art${oops:1"))),
                "g:bom:1 manages g:art${oops, whose artifactId art${oops is no valid Maven id (it keeps a reference "
                        + "that no pom defines)");
        failures.put(project("g:bom:1", managed(dep("g h:a:1"))),
                "g:bom:1 manages g h:a, whose groupId g h is no valid Maven id");
        failures.put(project("g:bom:1", managed("<dependency><artifactId>a</artifactId><version>1</version>"
                        + "</dependency>")),
                "g:bom:1 manages ?:a without groupId, which Maven does not accept");
        failures.put(project("g:bom:1", properties("empty="), managed(dep("g:${empty}:1"))),
                "g:bom:1 manages g:? without artifactId, which Maven does not accept");
        failures.put(project("g:bom:1", properties("grp=${other"), managed(dep("${grp}:a:1"))),
                "g:bom:1 manages ${other:a, whose groupId ${other is no valid Maven id (it keeps a reference that no "
                        + "pom defines)");
        failures.put(project("g:bom:1", managed("<dependency><groupId>${nope}</groupId><artifactId>a</artifactId>"
                        + "</dependency>")),
                "g:bom:1 manages ${nope}:a, whose groupId ${nope} is no valid Maven id (it keeps a reference that no "
                        + "pom defines)");
        failures.put(project("g:bom:1", managed(bomImport("org.imp:i:1"))),
                "org.imp:i:1 manages ${unknown.group}:x, whose groupId ${unknown.group} is no valid Maven id (it keeps "
                        + "a reference that no pom defines)");
        failures.put(project(":bom:", parent("g:parent:1")),
                "g:parent:1 manages g:${unknown.artifact}, whose artifactId ${unknown.artifact} is no valid Maven id "
                        + "(it keeps a reference that no pom defines)");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));
    }

    @Test
    void aGroupIdOrArtifactIdThatKeepsAReferenceMavenMayResolveSaysWhere() {
        // where Maven may give the reference a value, the id may be valid there: the error names where it may come
        // from, as for a version
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1",
                managed(dep("${project.description}:a:1"))));
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("t:bom:1", "<name>N</name>", managed(dep("g:${project.name}:1"))),
                "t:bom:1 manages g:${project.name}, whose artifactId keeps ${project.name}, which Maven may read from "
                        + "the model of t:bom:1, beyond the coordinates, packaging, parent and properties that the "
                        + "resolution reads");
        failures.put(project(":bom:", parent("g:parent:1")),
                "g:parent:1 manages ${project.description}:a, whose groupId keeps ${project.description}, which Maven "
                        + "may read from the model of g:bom:1, beyond the coordinates, packaging, parent and "
                        + "properties that the resolution reads");
        failures.put(project("t:bom:1", managed(dep("${env.GROUP}:a:1"))),
                "t:bom:1 manages ${env.GROUP}:a, whose groupId keeps ${env.GROUP}, which Maven may take from the "
                        + "machine that builds: a property of the JVM, of the environment or of Maven itself");
        failures.put(project("t:bom:1", managed(dep("g:${art}:1")),
                        "<profiles>" + profile("j", "<jdk>[1.8,)</jdk>", properties("art=a")) + "</profiles>"),
                "t:bom:1 manages g:${art}, whose artifactId keeps ${art}, which the profile j of t:bom:1 (JDK [1.8,)) "
                        + "sets on the machines that activate it, a profile that the resolution does not apply");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));
    }

    @Test
    void anInvalidIdInAProfileThatIsNotActiveDoesNotMatter() throws IOException {
        String bom = project("g:bom:1", managed(dep("g:x:1")), "<profiles>" + profile("off",
                "<property><name>enable</name></property>", managed(dep("${nope}:a:1"))) + "</profiles>");
        assertEquals(Map.of("g:x", "1"), versions(resolve(BomResolver.PomSource.NONE, bom)));
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
        String described = result.describeUnresolved().orElseThrow();
        assertTrue(described.startsWith("1 managed dependency keeps unresolved references: ${tcnative.version} "
                + "(declared in io.netty:netty-bom:4.1.69.Final, imported through io.netty:netty-bom:4.1.69.Final): "
                + "io.netty:netty-tcnative:linux-x86_64;"), described);
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
                        // depends on the machine, but changes nothing that the resolution reads
                        + profile("jdk11", "<jdk>[11,)</jdk>", properties("compiler.release=11"))
                        + profile("mac", "<os><family>mac</family></os>")
                        + "</profiles>");

        BomResolver.Result result = resolve(BomResolver.PomSource.NONE, bom);

        assertEquals(Map.of("g:x", "2", "g:y", "1", "g:replaced", "2", "g:extra", "1", "g:mode", "1"),
                versions(result));
        assertEquals(List.of("without-skip of g:bom:1", "not-fast of g:bom:1"), result.profiles());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void failsWhereAProfileThatDependsOnTheMachineChangesTheResult() {
        // Maven applies it on the machines that activate it, and JDK [1.8,) is every machine that runs Maven
        String jdk = "<jdk>[1.8,)</jdk>";
        Repo repo = new Repo()
                .add("g:parent:1", project("g:parent:1", properties("x=0"),
                        "<profiles>" + profile("jdk8+", jdk, properties("x=2")) + "</profiles>"))
                .add("org.imp:i:1", project("org.imp:i:1", managed(dep("g:a:${foo.version}")),
                        "<profiles>" + profile("jdk8+", jdk, properties("foo.version=1.0")) + "</profiles>"));
        String applies = ", and Maven applies it on the machines that activate it";
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("g:bom:1", properties("x=1"), managed(dep("g:a:${x}")),
                        "<profiles>" + profile("jdk8+", jdk, properties("x=2")) + "</profiles>"),
                "the profile jdk8+ of g:bom:1 (JDK [1.8,)) sets the property x, which the resolution of g:bom:1 reads"
                        + applies);
        failures.put(project("g:bom:1", managed(dep("g:a:${foo.version}")),
                        "<profiles>" + profile("jdk8+", jdk, properties("foo.version=1.0")) + "</profiles>"),
                "the profile jdk8+ of g:bom:1 (JDK [1.8,)) sets the property foo.version, which the resolution of "
                        + "g:bom:1 reads" + applies);
        failures.put(project(":bom:", parent("g:parent:1"), managed(dep("g:a:${x}"))),
                "the profile jdk8+ of g:parent:1 (JDK [1.8,)) sets the property x, which the resolution of g:bom:1 "
                        + "reads" + applies);
        failures.put(project("g:bom:1", managed(bomImport("org.imp:i:1"))),
                "the profile jdk8+ of org.imp:i:1 (JDK [1.8,)) sets the property foo.version, which the resolution of "
                        + "org.imp:i:1 reads" + applies);
        failures.put(project("g:bom:1", managed(bomImport("org.imp:i:${imp.version}")),
                        "<profiles>" + profile("jdk8+", jdk, properties("imp.version=1")) + "</profiles>"),
                "the profile jdk8+ of g:bom:1 (JDK [1.8,)) sets the property imp.version, which the resolution of "
                        + "g:bom:1 reads" + applies);
        failures.put(project("g:bom:1", managed(dep("g:a:1")),
                        "<profiles>" + profile("jdk8+", jdk, managed(dep("g:b:9"))) + "</profiles>"),
                "the profile jdk8+ of g:bom:1 (JDK [1.8,)) manages 1 dependency" + applies);
        // Maven rejects this entry where the profile is active
        failures.put(project("g:bom:1", managed(dep("g:a:2")),
                        "<profiles>" + profile("jdk8+", jdk, managed(dep("${nope}:b:9"))) + "</profiles>"),
                "the profile jdk8+ of g:bom:1 (JDK [1.8,)) manages 1 dependency" + applies);
        failures.put(project("g:bom:1", managed(dep("g:a:1")), "<profiles>"
                        + profile("mac", "<os><family>mac</family></os>", managed(dep("g:b:1"), dep("g:c:1")))
                        + "</profiles>"),
                "the profile mac of g:bom:1 (OS) manages 2 dependencies" + applies);
        failures.put(project("g:bom:1", managed(dep("g:a:${v}")), "<profiles>"
                        + profile("file", "<file><exists>${basedir}/x</exists></file>", properties("v=1"))
                        + "</profiles>"),
                "the profile file of g:bom:1 (file) sets the property v, which the resolution of g:bom:1 reads"
                        + applies);
        failures.put(project("g:bom:1", managed(dep("g:a:1")), "<profiles>"
                        + profile("java-vendor", "<property><name>!java.vendor</name></property>",
                                managed(dep("g:vendor:1")))
                        + "</profiles>"),
                "the profile java-vendor of g:bom:1 (property !java.vendor) manages 1 dependency" + applies);
        // active by default, but not in a pom where a profile is active on every machine
        failures.put(project("g:bom:1", managed(dep("g:a:${x}")), "<profiles>"
                        + profile("on", "<property><name>!off</name></property>", properties("y=1"))
                        + profile("dj", "<activeByDefault>true</activeByDefault>" + jdk, properties("x=1"))
                        + "</profiles>"),
                "the profile dj of g:bom:1 (JDK [1.8,)) sets the property x, which the resolution of g:bom:1 reads"
                        + applies);
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));
    }

    @Test
    void failsWhereAProfileThatDependsOnTheMachineDropsAProfileActiveByDefaultThatChangesTheResult()
            throws IOException {
        // Maven applies the profiles active by default of a pom only where no other profile of the pom is activated
        String jdk = "<jdk>[1.8,)</jdk>";
        String byDefault = "<activeByDefault>true</activeByDefault>";
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1", properties("x=0"), "<profiles>"
                + profile("d", byDefault, properties("x=1")) + profile("j", jdk) + "</profiles>"));
        String drops = ", and Maven drops it on the machines that activate the profile j of ";
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("g:bom:1", managed(dep("g:a:${x}")), "<profiles>" + profile("d", byDefault,
                        properties("x=1")) + profile("j", jdk, properties("unrelated=2")) + "</profiles>"),
                "the profile d of g:bom:1, active by default, sets the property x, which the resolution of g:bom:1 "
                        + "reads" + drops + "g:bom:1 (JDK [1.8,))");
        failures.put(project(":bom:", parent("g:parent:1"), managed(dep("g:a:${x}"))),
                "the profile d of g:parent:1, active by default, sets the property x, which the resolution of g:bom:1 "
                        + "reads" + drops + "g:parent:1 (JDK [1.8,))");
        failures.put(project("g:bom:1", managed(dep("g:a:1")), "<profiles>" + profile("d", byDefault,
                        managed(dep("g:only-default:5"))) + profile("j", jdk) + "</profiles>"),
                "the profile d of g:bom:1, active by default, manages 1 dependency" + drops + "g:bom:1 (JDK [1.8,))");
        // a profile active by default with a JDK condition is activated by it, and drops the other one
        failures.put(project("g:bom:1", managed(dep("g:a:${x}-${y}")), "<profiles>"
                        + profile("j", byDefault + jdk, properties("x=j")) + profile("d", byDefault, properties("y=d"))
                        + "</profiles>"),
                "the profile d of g:bom:1, active by default, sets the property y, which the resolution of g:bom:1 "
                        + "reads" + drops + "g:bom:1 (JDK [1.8,))");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));

        // alone, that one applies on every machine, whether its condition holds or not
        assertEquals(Map.of("g:a", "j"), versions(resolve(repo, project("g:bom:1", managed(dep("g:a:${x}")),
                "<profiles>" + profile("j", byDefault + "<jdk>[99,)</jdk>", properties("x=j")) + "</profiles>"))));
        // and a profile active by default that changes nothing the resolution reads may be dropped
        assertEquals(Map.of("g:a", "1"), versions(resolve(repo, project("g:bom:1", managed(dep("g:a:1")),
                "<profiles>" + profile("d", byDefault, properties("unused=1")) + profile("j", jdk) + "</profiles>"))));
    }

    @Test
    void failsOnAPropertyCycleThatProfilesDependingOnTheMachineForm() throws IOException {
        // Maven interpolates every property of the model on the machines that activate them too, where also two of
        // them may form one, or one may drop a profile active by default that replaces a property of a cycle
        String byDefault = "<activeByDefault>true</activeByDefault>";
        Repo repo = new Repo().add("g:parent:1", project("g:parent:1",
                "<profiles>" + profile("j8", "<jdk>[1.8,)</jdk>", properties("c1=${c2}")) + "</profiles>"));
        String cycle = "g:bom:1: the property references form a cycle: ";
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put(project("g:bom:1", managed(dep("g:a:1")), "<profiles>"
                        + profile("j", "<jdk>[1.8,)</jdk>", properties("c1=${c2}", "c2=${c1}")) + "</profiles>"),
                cycle + "c2 -> c1 -> c2, on the machines that activate the profile j of g:bom:1 (JDK [1.8,))");
        failures.put(project("g:bom:1", managed(dep("g:a:1")), "<profiles>"
                        + profile("jdk99", "<jdk>[99,)</jdk>", properties("b=${b}")) + "</profiles>"),
                cycle + "b -> b, on the machines that activate the profile jdk99 of g:bom:1 (JDK [99,))");
        failures.put(project("g:bom:1", managed(dep("g:a:1")), "<profiles>"
                        + profile("j8", "<jdk>[1.8,)</jdk>", properties("c1=${c2}"))
                        + profile("j11", "<jdk>[11,)</jdk>", properties("c2=${c1}")) + "</profiles>"),
                cycle + "c2 -> c1 -> c2, on the machines that activate the profile j8 of g:bom:1 (JDK [1.8,)) and the "
                        + "profile j11 of g:bom:1 (JDK [11,))");
        failures.put(project(":bom:", parent("g:parent:1"), managed(dep("g:a:1")), "<profiles>"
                        + profile("unix", "<os><family>unix</family></os>", properties("c2=${c1}")) + "</profiles>"),
                cycle + "c2 -> c1 -> c2, on the machines that activate the profile unix of g:bom:1 (OS) and the "
                        + "profile j8 of g:parent:1 (JDK [1.8,))");
        failures.put(project("g:bom:1", properties("x=${y}", "y=${x}"), managed(dep("g:a:1")), "<profiles>"
                        + profile("d", byDefault, properties("y=plain")) + profile("j", "<jdk>[1.8,)</jdk>")
                        + "</profiles>"),
                cycle + "y -> x -> y, on the machines that activate the profile j of g:bom:1 (JDK [1.8,))");
        failures.forEach((bom, message) ->
                assertEquals(message, assertThrows(IOException.class, () -> resolve(repo, bom), bom).getMessage()));

        // more than ten such profiles are too many combinations to try
        StringBuilder many = new StringBuilder("<profiles>");
        for (int i = 1; i <= 11; i++) {
            many.append(profile("jdk" + i, "<jdk>[" + i + ",)</jdk>", properties("p" + i + "=${q}")));
        }
        String tooMany = assertThrows(IOException.class, () -> resolve(repo, project("g:bom:1",
                managed(dep("g:a:1")), many + "</profiles>"))).getMessage();
        assertTrue(tooMany.startsWith("g:bom:1: 11 profiles that depend on the machine could add a property reference "
                + "cycle, too many to try each combination: profile jdk1 of g:bom:1 (JDK [1,)), "), tooMany);
        StringBuilder ten = new StringBuilder("<profiles>");
        for (int i = 1; i <= 10; i++) {
            ten.append(profile("jdk" + i, "<jdk>[" + i + ",)</jdk>", properties("p" + i + "=${q}")));
        }
        assertEquals(Map.of("g:a", "1"), versions(resolve(repo, project("g:bom:1", managed(dep("g:a:1")),
                ten + "</profiles>"))));

        // values with references that form no cycle in any combination
        assertEquals(Map.of("g:a", "1"), versions(resolve(repo, project("g:bom:1", properties("b=1", "x=${b}"),
                managed(dep("g:a:1")), "<profiles>" + profile("j8", "<jdk>[1.8,)</jdk>", properties("c=${b}"))
                        + profile("j11", "<jdk>[11,)</jdk>", properties("b=2", "d=${c}")) + "</profiles>"))));
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
        assertEquals(Map.of("x:y", "1.0-${oops", "x:z", "1"), versions(result));
        assertTrue(result.describeUnresolved().orElseThrow().startsWith("1 managed dependency keeps unresolved "
                + "references: ${oops (declared in g:bom:1): x:y;"), result.describeUnresolved().toString());
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

    @Test
    void aThreadWaitingForAPomGetsTheFailureOfItsFetchAsItWasThrown() throws Exception {
        // the fetch of the shared parent fails as a request does once requests stopped, while a second resolution
        // waits for it
        Http.StoppedException stopped = new Http.StoppedException("not requesting g:parent:1: requests stopped");
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        BomResolver resolver = new BomResolver((groupId, artifactId, version) -> {
            reads.incrementAndGet();
            fetching.countDown();
            try {
                fail.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
            throw stopped;
        });
        byte[] first = project("g:bom-a:1", parent("g:parent:1")).getBytes(StandardCharsets.UTF_8);
        byte[] second = project("g:bom-b:1", parent("g:parent:1")).getBytes(StandardCharsets.UTF_8);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<BomResolver.Result> fetches = executor.submit(() -> resolver.resolve(first, "1"));
                fetching.await();
                AtomicReference<Thread> waiter = new AtomicReference<>();
                Future<BomResolver.Result> waits = executor.submit(() -> {
                    waiter.set(Thread.currentThread());
                    return resolver.resolve(second, "1");
                });
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (waiter.get() == null || waiter.get().getState() != Thread.State.WAITING) {
                    assertTrue(System.nanoTime() < deadline, "the second resolution never waited for the fetch");
                    Thread.sleep(5);
                }
                fail.countDown();

                assertSame(stopped, assertThrows(ExecutionException.class, fetches::get).getCause());
                assertSame(stopped, assertThrows(ExecutionException.class, waits::get).getCause(),
                        "the waiting resolution fails with the failure of the fetch, a stop of the requests");
            } finally {
                fail.countDown();
            }
        }
        assertEquals(1, reads.get());
    }

    @Test
    void aThreadWaitingForAPomWhoseFetchThrowsAnErrorFailsInsteadOfWaitingForever() throws Exception {
        StackOverflowError error = new StackOverflowError("deep pom");
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        BomResolver resolver = new BomResolver((groupId, artifactId, version) -> {
            fetching.countDown();
            try {
                fail.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
            throw error;
        });
        byte[] first = project("g:bom-a:1", parent("g:parent:1")).getBytes(StandardCharsets.UTF_8);
        byte[] second = project("g:bom-b:1", parent("g:parent:1")).getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BomResolver.Result> fetches = executor.submit(() -> resolver.resolve(first, "1"));
            fetching.await();
            AtomicReference<Thread> waiter = new AtomicReference<>();
            Future<BomResolver.Result> waits = executor.submit(() -> {
                waiter.set(Thread.currentThread());
                return resolver.resolve(second, "1");
            });
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (waiter.get() == null || waiter.get().getState() != Thread.State.WAITING) {
                assertTrue(System.nanoTime() < deadline, "the second resolution never waited for the fetch");
                Thread.sleep(5);
            }
            fail.countDown();

            assertSame(error, assertThrows(ExecutionException.class, fetches::get).getCause());
            Throwable waited = assertThrows(ExecutionException.class, () -> waits.get(10, TimeUnit.SECONDS),
                    "the waiting resolution ends").getCause();
            assertTrue(waited instanceof IOException, waited.toString());
            assertSame(error, waited.getCause());
        } finally {
            fail.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void resolutionsInParallelThatShareAFailedFetchFailWithItsStop() throws Exception {
        // as bom resolves releases: Parallel.map gets the same exception from both tasks and must rethrow it as it is
        Http.StoppedException stopped = new Http.StoppedException("not requesting g:parent:1: requests stopped");
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        BomResolver resolver = new BomResolver((groupId, artifactId, version) -> {
            fetching.countDown();
            try {
                fail.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("interrupted");
            }
            throw stopped;
        });
        AtomicReference<Thread> waiter = new AtomicReference<>();
        // once the second resolution waits for the fetch of the first, the fetch fails
        Thread release = new Thread(() -> {
            long deadline = System.nanoTime() + 10_000_000_000L;
            try {
                while (System.nanoTime() < deadline
                        && (waiter.get() == null || waiter.get().getState() != Thread.State.WAITING)) {
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fail.countDown();
        });
        release.start();
        try {
            IOException e = assertThrows(IOException.class, () -> Parallel.map(List.of("a", "b"), 2, name -> {
                if (name.equals("b")) {
                    try {
                        fetching.await();
                    } catch (InterruptedException interrupted) {
                        throw new InterruptedIOException("interrupted");
                    }
                    waiter.set(Thread.currentThread());
                }
                return resolver.resolve(project("g:bom-" + name + ":1", parent("g:parent:1"))
                        .getBytes(StandardCharsets.UTF_8), "1");
            }));
            assertSame(stopped, e);
            assertEquals(0, e.getSuppressed().length);
        } finally {
            fail.countDown();
            release.join();
        }
    }
}
