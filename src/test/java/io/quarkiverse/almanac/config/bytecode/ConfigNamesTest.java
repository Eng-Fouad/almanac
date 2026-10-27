package io.quarkiverse.almanac.config.bytecode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The processor's naming rules, with test vectors and examples confirmed against the 3.25.0
/// model.
class ConfigNamesTest {

    @Test
    void hyphenatesLikeTheProcessor() {
        Map<String, String> vectors = Map.ofEntries(
                // Appendix B
                Map.entry("connectionTTL", "connection-ttl"),
                Map.entry("allowedDBHost", "allowed-db-host"),
                Map.entry("http2PushEnabled", "http2-push-enabled"),
                Map.entry("resumeOn404", "resume-on404"),
                Map.entry("suppress4xxErrors", "suppress4xx-errors"),
                Map.entry("IPv4Address", "i-pv4-address"),
                Map.entry("JBossLogManager", "jboss-log-manager"),
                Map.entry("FOO_BAR", "foo_bar"),
                // confirmed against the 3.25.0 model
                Map.entry("coordinatorURL", "coordinator-url"),
                Map.entry("diskURI", "disk-uri"),
                Map.entry("destinationCACertificate", "destination-ca-certificate"),
                Map.entry("startTLS", "start-tls"),
                Map.entry("useAsyncDNS", "use-async-dns"),
                Map.entry("allowXForwarded", "allow-x-forwarded"),
                Map.entry("http2", "http2"),
                Map.entry("oauth2BearerFormat", "oauth2-bearer-format"),
                Map.entry("useLayer7Proxy", "use-layer7-proxy"),
                Map.entry("contrast10pct", "contrast10pct"),
                Map.entry("keycloakXImage", "keycloak-x-image"),
                // derived from the algorithm
                Map.entry("HTTP2Config", "http2-config"),
                Map.entry("maxHTTP2Streams", "max-http2-streams"),
                Map.entry("URLConnection", "url-connection"),
                Map.entry("ioURing", "io-u-ring"),
                Map.entry("already-hyphenated", "already-hyphenated"),
                Map.entry("Foo-Bar", "foo--bar"),
                Map.entry("aB", "a-b"),
                Map.entry("X", "x"),
                Map.entry("handle100ContinueAutomatically", "handle100-continue-automatically"),
                Map.entry("", ""));
        vectors.forEach((input, expected) -> assertEquals(expected, ConfigNames.hyphenate(input), input));
    }

    @Test
    void splitsWordsAtCapitalsDigitsStickLeft() {
        assertEquals(List.of("max", "HTTP2", "Streams"), ConfigNames.words("maxHTTP2Streams"));
        assertEquals(List.of("JBoss", "Log", "Manager"), ConfigNames.words("JBossLogManager"));
        assertEquals(List.of("I", "Pv4", "Address"), ConfigNames.words("IPv4Address"));
        assertEquals(List.of("resume", "On404"), ConfigNames.words("resumeOn404"));
    }

    @Test
    void hyphenatesEnumValues() {
        Map<String, String> vectors = Map.ofEntries(
                // Appendix B
                Map.entry("READ_UNCOMMITTED", "read-uncommitted"),
                Map.entry("ClusterIP", "cluster-ip"),
                Map.entry("EdDSA", "ed-dsa"),
                Map.entry("A256GCMKW", "a256-gcmkw"),
                Map.entry("File_System", "file-system"),
                Map.entry("LOCAL_USE_0", "local-use-0"),
                Map.entry("TLSv1_2", "tl-sv1-2"),
                // more cases
                Map.entry("RFC5424", "rfc5424"),
                Map.entry("CLOCK_DAEMON2", "clock-daemon2"),
                Map.entry("IfNotPresent", "if-not-present"),
                Map.entry("NonIndexed", "non-indexed"),
                Map.entry("ES256K", "es256k"),
                Map.entry("CACHING_SHA2_PASSWORD", "caching-sha2-password"),
                Map.entry("H2C", "h2-c"),
                Map.entry("A__B--C", "a-b-c"));
        vectors.forEach((input, expected) -> assertEquals(expected, ConfigNames.hyphenateEnumValue(input), input));
    }

    @Test
    void derivesLegacyRootPrefixes() {
        String derive = ConfigNames.HYPHENATED_ELEMENT_NAME;
        // Appendix B getRootPrefix
        assertEquals("quarkus.banner", ConfigNames.rootPrefix("quarkus", derive, "BannerRuntimeConfig", "RUN_TIME"));
        assertEquals("quarkus.grpc-client",
                ConfigNames.rootPrefix("quarkus", derive, "GrpcClientBuildTimeConfig", "BUILD_TIME"));
        assertEquals("quarkus.index-dependency",
                ConfigNames.rootPrefix("quarkus", derive, "IndexDependencyConfiguration", "BUILD_TIME"));
        assertEquals("quarkus", ConfigNames.rootPrefix("quarkus", ConfigNames.PARENT, "ConfigBuildTimeConfig",
                "BUILD_TIME"));
        assertEquals("quarkus.http",
                ConfigNames.rootPrefix("quarkus", "http", "HttpBuildTimeConfig", "BUILD_AND_RUN_TIME_FIXED"));
        // the naming table
        assertEquals("quarkus.arc", ConfigNames.rootPrefix("quarkus", derive, "ArcConfig", "BUILD_TIME"));
        assertEquals("quarkus.jackson",
                ConfigNames.rootPrefix("quarkus", derive, "JacksonBuildTimeConfig", "BUILD_AND_RUN_TIME_FIXED"));
        assertEquals("quarkus.grpc", ConfigNames.rootPrefix("quarkus", derive, "GrpcConfiguration", "RUN_TIME"));
        assertEquals("quarkus.lambda-http", ConfigNames.rootPrefix("quarkus", derive, "LambdaHttpConfig", "RUN_TIME"));
        assertEquals("quarkus.devservices",
                ConfigNames.rootPrefix("quarkus", "devservices", "GlobalDevServicesConfig", "BUILD_TIME"));
        assertEquals("quarkus", ConfigNames.rootPrefix("quarkus", "", "AnyConfig", "RUN_TIME"));
        // the phase suffix depends on the phase and is matched case-insensitively; stripping repeats
        assertEquals("quarkus.foo-build-time",
                ConfigNames.rootPrefix("quarkus", derive, "FooBuildTimeConfig", "RUN_TIME"));
        assertEquals("quarkus.foo", ConfigNames.rootPrefix("quarkus", derive, "FooRuntimeConfig", "RUN_TIME"));
        assertEquals("quarkus.foo", ConfigNames.rootPrefix("quarkus", derive, "FooConfigConfig", "BUILD_TIME"));
        assertEquals("quarkus.foo", ConfigNames.rootPrefix("quarkus", derive, "FooConfigurationConfig", "BUILD_TIME"));
        // BOOTSTRAP (3.0 - 3.2) strips "Bootstrap", as the processor of that era did
        assertEquals("quarkus.kubernetes",
                ConfigNames.rootPrefix("quarkus", derive, "KubernetesConfigBootstrapConfig", "BOOTSTRAP"));
        // an explicit prefix, alone with "<<parent>>" or "", joined with any other name
        assertEquals("acme", ConfigNames.rootPrefix("acme", ConfigNames.PARENT, "AnyConfig", "BUILD_TIME"));
        assertEquals("acme.x", ConfigNames.rootPrefix("acme", "x", "AnyConfig", "BUILD_TIME"));
        assertEquals("acme.any", ConfigNames.rootPrefix("acme", derive, "AnyConfig", "BUILD_TIME"));
        assertEquals("x", ConfigNames.rootPrefix("", "x", "AnyConfig", "BUILD_TIME"));
    }

    @Test
    void usesMappingPrefixesVerbatim() {
        assertEquals("quarkus.hibernate-orm", ConfigNames.rootPrefix("quarkus.hibernate-orm", "",
                "HibernateOrmRuntimeConfig", "RUN_TIME"));
        assertEquals("quarkus.log", ConfigNames.rootPrefix("quarkus.log", "", "LogRuntimeConfig", "RUN_TIME"));
        // never hyphenated or shortened, dots included
        assertEquals("quarkus.camelCase.x", ConfigNames.rootPrefix("quarkus.camelCase.x", "", "XConfig", "BUILD_TIME"));
        assertEquals("", ConfigNames.rootPrefix("", "", "XConfig", "BUILD_TIME"));
    }

    @Test
    void normalizesDurations() {
        Map<String, String> vectors = Map.of("10s", "10S", "60", "60S", "0.100s", "0.100S", "PT1M", "PT1M",
                "8h", "8H", "99999999999", "99999999999", "1.5", "1.5", "-1", "-1S", "", "");
        vectors.forEach((input, expected) -> assertEquals(expected, ConfigNames.normalizeDuration(input), input));
    }

    @Test
    void appendsPathsAndMapKeys() {
        assertEquals("quarkus.x", ConfigNames.appendPath("quarkus.x", ConfigNames.PARENT));
        assertEquals("quarkus.x.config.profile.parent", ConfigNames.appendPath("quarkus.x", "config.profile.parent"));
        assertEquals(".\"group-id:artifact-id\"", ConfigNames.mapKey("group-id:artifact-id"));
        assertEquals(".\"<identifier>\"", ConfigNames.mapKey("<identifier>"));
    }
}
