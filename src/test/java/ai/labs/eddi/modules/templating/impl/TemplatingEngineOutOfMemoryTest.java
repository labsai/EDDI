/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateEngineException;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.TemplateLimitExceededException;
import io.quarkus.qute.Engine;
import io.quarkus.qute.ValueResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A render that ran out of memory threw an {@code Error} past
 * {@code processTemplate}'s handling, failing the turn with no record of which
 * template did it, while the same class of failure caught in time — a
 * {@link TemplateLimitExceededException} — was reported like any other
 * malformed template. Both are now.
 */
@DisplayName("TemplatingEngine — OutOfMemoryError and the output limit are reported alike")
class TemplatingEngineOutOfMemoryTest {

    private static Engine sourceWith(String name) {
        return Engine.builder().addDefaults()
                .addValueResolver(ValueResolver.builder().appliesTo(ctx -> name.equals(ctx.getName())).resolveSync(ctx -> {
                    if ("exhaust".equals(name)) {
                        throw new OutOfMemoryError("Java heap space");
                    }
                    throw new TemplateLimitExceededException("a template expression produced too much");
                }).build())
                .build();
    }

    @Test
    @DisplayName("an OutOfMemoryError raised while rendering becomes a TemplateEngineException naming the bound")
    void outOfMemoryIsReported() {
        var engine = new TemplatingEngine(sourceWith("exhaust"));

        var e = assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{x.exhaust}", Map.of("x", "value")));

        assertTrue(e.getMessage().contains("exhausted the available memory"), e.getMessage());
        assertTrue(e.getMessage().contains("eddi.templating.max-output-chars"), e.getMessage());
        assertTrue(hasCause(e, OutOfMemoryError.class), "the Error travels as the cause");
    }

    @Test
    @DisplayName("a TemplateLimitExceededException raised while rendering is reported with its own message")
    void limitIsReported() {
        var engine = new TemplatingEngine(sourceWith("limit"));

        var e = assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{x.limit}", Map.of("x", "value")));

        assertTrue(e.getMessage().contains("produced too much"), e.getMessage());
        assertTrue(hasCause(e, TemplateLimitExceededException.class));
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
