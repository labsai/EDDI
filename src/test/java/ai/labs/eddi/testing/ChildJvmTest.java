/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChildJvm")
class ChildJvmTest {

    /**
     * The child: echoes its heap ceiling and arguments, exits with the first one.
     */
    public static final class Echo {
        public static void main(String[] args) {
            System.out.println("max=" + Runtime.getRuntime().maxMemory());
            System.out.println("args=" + String.join(",", args));
            int status;
            try {
                status = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                status = 2; // the tests always pass a number; anything else is a test bug
            }
            System.exit(status);
        }
    }

    /** The child that never ends on its own. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(Duration.ofMinutes(5));
        }
    }

    @Test
    void runsTheMainClassWithItsOwnHeapAndArguments() throws Exception {
        ChildJvm.Result result = ChildJvm.run(Echo.class, List.of("-Xmx48m"), List.of("7", "a b", "c"), Duration.ofMinutes(1));

        assertEquals(7, result.exitCode(), result.output());
        assertFalse(result.timedOut());
        assertEquals("args=7,a b,c", result.lastLine());
        long max = Long.parseLong(result.output().lines().filter(line -> line.startsWith("max=")).findFirst().orElseThrow().substring(4));
        assertTrue(max <= 48L * 1024 * 1024, "child heap " + max);
    }

    @Test
    void killsAChildThatOutlivesItsTimeout() throws Exception {
        ChildJvm.Result result = ChildJvm.run(Sleeper.class, List.of("-Xmx32m"), List.of(), Duration.ofSeconds(2));

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }
}
