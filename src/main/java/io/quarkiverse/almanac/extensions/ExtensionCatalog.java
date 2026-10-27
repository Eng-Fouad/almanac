package io.quarkiverse.almanac.extensions;

/// The extension catalogs that Quarkus publishes with each release, newest format first. Both list the extensions of
/// `io.quarkus:quarkus-bom` with the metadata of their `META-INF/quarkus-extension.yaml`; [ExtensionCatalogReader]
/// reads either one.
public enum ExtensionCatalog {

    /// `io.quarkus:quarkus-bom-quarkus-platform-descriptor:<v>:json:<v>`, the extension catalog of the Quarkus
    /// registry, published from 1.10.0.CR1 on: each extension names its artifact as
    /// `groupId:artifactId:classifier:type:version` in `artifact`.
    PLATFORM_DESCRIPTOR("quarkus-bom-quarkus-platform-descriptor", true),

    /// `io.quarkus:quarkus-bom-descriptor-json:<v>` (type `json`, no classifier), published from 0.26.0 to 1.9.2.Final:
    /// each extension has `group-id`, `artifact-id` and `version`; in 0.26, `groupId`, `artifactId` and `version`,
    /// and no `metadata` (its `guide` sits next to `name`).
    BOM_DESCRIPTOR_JSON("quarkus-bom-descriptor-json", false);

    /// Group of both catalog artifacts.
    public static final String GROUP_ID = "io.quarkus";

    /// File extension (Maven type) of both catalog artifacts.
    public static final String EXTENSION = "json";

    private final String artifactId;
    private final boolean versionClassifier;

    ExtensionCatalog(String artifactId, boolean versionClassifier) {
        this.artifactId = artifactId;
        this.versionClassifier = versionClassifier;
    }

    /// The catalog's artifactId.
    public String artifactId() {
        return artifactId;
    }

    /// The classifier of a release's catalog: the release itself for [#PLATFORM_DESCRIPTOR], none (null) for
    /// [#BOM_DESCRIPTOR_JSON].
    public String classifier(String version) {
        return versionClassifier ? version : null;
    }

    /// Maven coordinates of a release's catalog, e.g.
    /// `io.quarkus:quarkus-bom-quarkus-platform-descriptor:3.20.6:json:3.20.6`.
    public String coordinates(String version) {
        String coordinates = GROUP_ID + ":" + artifactId + ":" + version + ":" + EXTENSION;
        return versionClassifier ? coordinates + ":" + version : coordinates;
    }
}
