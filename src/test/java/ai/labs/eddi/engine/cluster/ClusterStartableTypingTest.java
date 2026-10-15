/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.events.JetStreamEventBus;
import ai.labs.eddi.engine.cluster.rpc.NatsClusterRpc;
import jakarta.enterprise.inject.Typed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClusterBootstrap} starts the cluster layer by iterating
 * {@code Instance<ClusterStartable>}. The NATS beans are {@code @Typed} to
 * their own class so they never compete with the producers in
 * {@link ClusterProducers} — and a bean typed to its own class only is not a
 * {@code ClusterStartable} as far as CDI is concerned. The RPC and the event
 * bus were exactly that: neither ever subscribed, so every cross-node cancel,
 * GDPR stop and cache invalidation went nowhere while the unit tests, which
 * call {@code startCluster()} themselves, stayed green.
 */
@DisplayName("every ClusterStartable is visible to ClusterBootstrap")
class ClusterStartableTypingTest {

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);

    /**
     * A top-level class declaration (at the start of a line, so not prose in a
     * comment).
     */
    private static final Pattern DECLARATION = Pattern
            .compile("^(?:public\\s+)?(?:final\\s+|abstract\\s+)*class\\s+(\\w+)[^{]*?\\bimplements\\b[^{]*\\bClusterStartable\\b",
                    Pattern.MULTILINE);

    @Test
    void typedStartablesIncludeTheStartableType() throws Exception {
        List<Class<?>> startables = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = read(source);
                Matcher m = DECLARATION.matcher(text);
                Matcher pkg = PACKAGE.matcher(text);
                if (m.find() && pkg.find()) {
                    startables.add(Class.forName(pkg.group(1) + "." + m.group(1), false, getClass().getClassLoader()));
                }
            }
        }
        assertTrue(startables.contains(NatsClusterRpc.class) && startables.contains(JetStreamEventBus.class),
                "the scan must find the NATS RPC and the event bus, or it checks nothing: " + startables);

        List<String> invisible = new ArrayList<>();
        for (Class<?> type : startables) {
            assertTrue(ClusterStartable.class.isAssignableFrom(type), type + " was matched but does not implement ClusterStartable");
            Typed typed = type.getAnnotation(Typed.class);
            if (typed != null && !Arrays.asList(typed.value()).contains(ClusterStartable.class)) {
                invisible.add(type.getName());
            }
        }
        assertEquals(List.of(), invisible,
                "these beans implement ClusterStartable but @Typed hides that type, so ClusterBootstrap never starts them");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
