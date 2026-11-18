/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateEngineException;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactoryTest.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactoryTest.quarkusLikeSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine behind {@code processExpressionsTemplate} renders agent-authored
 * templates too, so it has to be exactly as restricted as the ordinary runtime
 * engine. Each test renders through {@code processExpressionsTemplate} and
 * asserts the restriction itself — not that the two engines share a builder.
 */
@DisplayName("Notation-safe engine — same restrictions as the runtime engine")
class NotationSafeEngineRestrictionsTest {

    private static final String ENV_KEY = System.getenv("PATH") != null ? "PATH" : "Path";

    /** A getter that takes its time, so a render can outlast a short deadline. */
    public static final class Slow {
        public String getValue() throws InterruptedException {
            Thread.sleep(5);
            return "v";
        }
    }

    private static Settings settings(int maxOutputChars, int maxIterations, long timeoutMillis) {
        var defaults = Settings.defaults();
        return new Settings(maxOutputChars, maxIterations, defaults.strictRendering(), defaults.removeStandaloneLines(),
                defaults.iterationMetadataPrefix(), timeoutMillis);
    }

    private static String expressions(String template, Map<String, Object> data) throws TemplateEngineException {
        return new TemplatingEngine(quarkusLikeSource()).processExpressionsTemplate(template, data);
    }

    @Test
    @DisplayName("config:, inject: and cdi: resolve to nothing")
    void blockedNamespaces() throws Exception {
        String config = "[{config:" + ENV_KEY + "}]";
        assertNotEquals("[]", quarkusLikeSource().parse(config).render(Map.of()), "precondition: the source reads the environment");
        assertEquals("[]", expressions(config, Map.of()));
        assertNotEquals("[]", quarkusLikeSource().parse("[{inject:bean.secret}]").render(Map.of()), "precondition: inject: works");
        assertEquals("[][]", expressions("[{inject:bean.secret}][{cdi:bean.secret}]", Map.of()));
    }

    @Test
    @DisplayName("str:eval does not render data as a template")
    void strEvalIsBlocked() throws Exception {
        Map<String, Object> data = Map.of("payload", "{config:" + ENV_KEY + "}");
        assertEquals("[]", expressions("[{str:eval(payload)}]", data));
    }

    @Test
    @DisplayName("methods with arguments are not invoked")
    void noReflectiveCalls() throws Exception {
        assertEquals("[]", expressions("[{item.shout('!')}]", Map.of("item", new Item("abc"))));
    }

    @Test
    @DisplayName("{#include} and {#eval} do not exist")
    void sectionsAreRejected() {
        var engine = new TemplatingEngine(quarkusLikeSource());
        assertThrows(TemplateEngineException.class, () -> engine.processExpressionsTemplate("{#eval payload /}", Map.of("payload", "x")));
        assertThrows(TemplateEngineException.class, () -> engine.processExpressionsTemplate("{#include other /}", Map.of()));
    }

    @Test
    @DisplayName("the loop budget applies")
    void loopBudget() throws Exception {
        var engine = new TemplatingEngine(quarkusLikeSource(), settings(0, 100, Settings.DEFAULT_TIMEOUT_MILLIS));
        assertEquals(".".repeat(80), engine.processExpressionsTemplate("{#for a in 80}.{/for}", Map.of()));
        var e = assertThrows(TemplateEngineException.class,
                () -> engine.processExpressionsTemplate("{#for a in 20}{#for b in 20}.{/for}{/for}", Map.of()));
        assertTrue(e.getMessage().contains("eddi.templating.max-iterations"), e.getMessage());
    }

    @Test
    @DisplayName("the output cap applies, to the whole output and to one value")
    void outputCap() {
        var engine = new TemplatingEngine(quarkusLikeSource(), settings(100, 1000, Settings.DEFAULT_TIMEOUT_MILLIS));
        var whole = assertThrows(TemplateEngineException.class, () -> engine.processExpressionsTemplate("{#for i in 50}xxxxx{/for}", Map.of()));
        assertTrue(whole.getMessage().contains("eddi.templating.max-output-chars"), whole.getMessage());
        var one = assertThrows(TemplateEngineException.class,
                () -> engine.processExpressionsTemplate("{#if big}ok{/if}", Map.of("big", "y".repeat(500))));
        assertTrue(one.getMessage().contains("eddi.templating.max-output-chars"), one.getMessage());
    }

    @Test
    @DisplayName("the render deadline applies")
    void deadline() {
        var engine = new TemplatingEngine(quarkusLikeSource(), settings(0, 1000, 30));
        var e = assertThrows(TemplateEngineException.class,
                () -> engine.processExpressionsTemplate("{#for i in 200}{slow.value}{/for}", Map.of("slow", new Slow())));
        assertTrue(e.getMessage().contains("quarkus.qute.timeout"), e.getMessage());
    }

    @Test
    @DisplayName("a missing value renders as nothing, not NOT_FOUND")
    void missingRendersEmpty() throws Exception {
        assertEquals("choose()", expressions("choose({nothing.here})", Map.of()));
    }
}
