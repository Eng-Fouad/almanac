package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.InMemoryJar;
import io.quarkiverse.almanac.config.VersionJars;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigMembersTest {

    @Test
    void visitsRootsSuperTypesAndGroupsLikeTheBytecodeReader() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(Fixtures.annotations(RetentionPolicy.RUNTIME));
        sources.put("com/acme/members/Base.java", """
                package com.acme.members;
                public interface Base {
                    String base();
                }
                """);
        sources.put("com/acme/members/Root.java", """
                package com.acme.members;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                public interface Root extends Base {
                    Optional<Group> group();
                    List<Leaf> leaves();
                    Map<String, Map<String, Group>> nested();
                    Group[] array();
                    String toString();
                    void run();
                    default int helper() {
                        return 0;
                    }
                    static Root create() {
                        return null;
                    }
                }
                """);
        sources.put("com/acme/members/Group.java", """
                package com.acme.members;
                public interface Group {
                    int value();
                }
                """);
        sources.put("com/acme/members/Leaf.java", """
                package com.acme.members;
                public class Leaf {
                    public String notVisited;
                }
                """);
        sources.put("com/acme/members/LegacyRoot.java", """
                package com.acme.members;
                public class LegacyRoot extends LegacyBase {
                    public static final String CONSTANT = "c";
                    public LegacyGroup group;
                    public java.util.Map<String, LegacyGroup> groups;
                    public Leaf leaf;
                    public Base base;
                }
                """);
        sources.put("com/acme/members/LegacyBase.java", """
                package com.acme.members;
                public class LegacyBase {
                    public String inherited;
                }
                """);
        sources.put("com/acme/members/LegacyGroup.java", """
                package com.acme.members;
                @io.quarkus.runtime.annotations.ConfigGroup
                public class LegacyGroup {
                    public int value;
                }
                """);
        InMemoryJar jar = InMemoryJar.of("quarkus-members", "1.0").with(ConfigMembers.ROOTS_ENTRY,
                "com.acme.members.Root\ncom.acme.members.LegacyRoot\r\ncom.acme.members.Missing\n\n");
        TestCompiler.compile(sources).forEach((name, bytes) -> jar.with(name + ".class", bytes));
        VersionJars jars = new VersionJars("3.8.6.1", List.of(jar), j -> Optional.empty(), 2);

        ConfigMembers.Result result = ConfigMembers.collect(jars, List.of(jar));

        assertEquals(List.of("Root#group()", "Root#leaves()", "Root#nested()", "Root#array()", "LegacyRoot#group",
                "LegacyRoot#groups", "LegacyRoot#leaf", "LegacyRoot#base", "Base#base()", "Group#value()",
                "LegacyBase#inherited", "LegacyGroup#value"), result.members().stream()
                .map(member -> member.key().substring("com/acme/members/".length()))
                .toList());
        assertEquals(6, result.types());
        assertEquals(List.of("com/acme/members/Missing"), result.missingClasses());
    }

    @Test
    void unwrapsContainersToTheGroupType() {
        assertEquals(Optional.of("a/G"), ConfigMembers.unwrap("La/G;"));
        assertEquals(Optional.of("a/G"), ConfigMembers.unwrap("Ljava/util/Optional<La/G;>;"));
        assertEquals(Optional.of("a/G"), ConfigMembers.unwrap("Ljava/util/List<+La/G;>;"));
        assertEquals(Optional.of("a/G"), ConfigMembers.unwrap("Ljava/util/Set<La/G;>;"));
        assertEquals(Optional.of("a/G"), ConfigMembers.unwrap("[La/G;"));
        assertEquals(Optional.of("a/G"),
                ConfigMembers.unwrap("Ljava/util/Map<Ljava/lang/String;Ljava/util/Map<Ljava/lang/String;La/G;>;>;"));
        assertEquals(Optional.of("a/Outer$Inner"), ConfigMembers.unwrap("La/Outer<TT;>.Inner;"));
        assertEquals(Optional.of("java/util/Optional"), ConfigMembers.unwrap("Ljava/util/Optional;"));
        assertEquals(Optional.empty(), ConfigMembers.unwrap("I"));
        assertEquals(Optional.empty(), ConfigMembers.unwrap("TT;"));
        assertEquals(Optional.empty(), ConfigMembers.unwrap("Ljava/util/List<*>;"));
    }
}
