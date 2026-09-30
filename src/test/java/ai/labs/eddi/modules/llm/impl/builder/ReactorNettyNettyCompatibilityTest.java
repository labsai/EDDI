/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.netty.resources.LoopResources;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Azure OpenAI provider talks HTTP through azure-core-http-netty, i.e.
 * Reactor Netty on the Netty that Quarkus manages. {@code pom.xml} overrides
 * {@code reactor-netty-http} for a CVE, and the override can pick a Reactor
 * Netty line built against a newer Netty than Quarkus ships: 1.3.x needs Netty
 * 4.2, and on Quarkus's Netty 4.1 its default event loop throws
 * {@code NoClassDefFoundError: io.netty.channel.MultiThreadIoEventLoopGroup} on
 * the first request. Nothing else in the unit suite builds that event loop, so
 * the mismatch otherwise surfaces only when a deployment first calls Azure.
 */
class ReactorNettyNettyCompatibilityTest {

    @Test
    @DisplayName("Reactor Netty links against the Netty the build resolves")
    void clientEventLoopLinksAgainstTheResolvedNetty() {
        LoopResources loops = LoopResources.create("eddi-compat-test", 1, true);
        try {
            loops.onClient(false);
        } catch (Throwable t) {
            // Only a linkage failure is the incompatibility. Opening the NIO selector
            // needs a loopback socket pair, which sandboxed runners can refuse; that
            // happens after every class is resolved, so it says nothing either way.
            for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
                if (c instanceof LinkageError) {
                    fail("reactor-netty and netty are on incompatible lines; see the reactor-netty-http override in pom.xml", t);
                }
            }
        } finally {
            loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(10));
        }
    }
}
