/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Expression;
import io.quarkus.qute.ResultMapper;
import io.quarkus.qute.Results;
import io.quarkus.qute.TemplateNode.Origin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template that references a property nobody set must render nothing there —
 * not the word {@code NOT_FOUND}.
 * <p>
 * {@code quarkus.qute.strict-rendering=false} only stops the render from
 * throwing. The missing value still resolves to Qute's NotFound sentinel, and
 * the default mapper writes the literal constant {@code NOT_FOUND} into the
 * output. On a live instance that reached end users verbatim — "Your favourite
 * programming language is: NOT_FOUND." — and equally reached system prompts and
 * HTTP call bodies, wherever an optional property was referenced. Dev mode
 * defaults to throwing instead, so the two profiles did not even agree, and the
 * documented guard ({@code .orEmpty}) is for iterables and fails on NotFound,
 * so config authors following the docs had nothing that worked.
 * <p>
 * The behaviour is a configuration property rather than code, so this test pins
 * both halves: that leaving it unset really does produce the literal, and that
 * {@code application.properties} sets it.
 */
@DisplayName("a missing property renders as nothing")
class MissingPropertyRenderingTest {

    private static final String TEMPLATE = "Your favourite programming language is: {properties.language}.";

    /** Equivalent of Quarkus's package-private {@code PropertyNotFoundNoop}. */
    private static final ResultMapper RENDER_NOTHING = new ResultMapper() {
        @Override
        public boolean appliesTo(Origin origin, Object result) {
            return Results.isNotFound(result);
        }

        @Override
        public String map(Object result, Expression expression) {
            return "";
        }
    };

    private static String render(Engine engine) {
        return engine.parse(TEMPLATE).render(Map.of("properties", Map.of("other", "value")));
    }

    @Test
    @DisplayName("lenient rendering alone still emits the literal NOT_FOUND")
    void withoutTheStrategyTheLiteralLeaks() {
        String rendered = render(Engine.builder().addDefaults().strictRendering(false).build());

        assertTrue(rendered.contains("NOT_FOUND"),
                "precondition: this is exactly what reached end users, and why the config property is required");
    }

    @Test
    @DisplayName("with the NOOP strategy the expression renders empty")
    void withTheStrategyNothingIsRendered() {
        String rendered = render(Engine.builder().addDefaults().strictRendering(false)
                .addResultMapper(RENDER_NOTHING).build());

        assertEquals("Your favourite programming language is: .", rendered);
    }

    @Test
    @DisplayName("application.properties selects NOOP, in every profile")
    void configurationSelectsNoop() {
        Path root = Path.of("").toAbsolutePath();
        String properties;
        try {
            properties = Files.readString(root.resolve("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertTrue(properties.contains("quarkus.qute.property-not-found-strategy=NOOP"),
                "without this line a missing property renders the literal NOT_FOUND into user-visible output");
        assertTrue(properties.contains("quarkus.qute.strict-rendering=false"),
                "NOOP only applies while strict rendering is off");
        assertTrue(!properties.contains("%prod.quarkus.qute.property-not-found-strategy")
                && !properties.contains("%dev.quarkus.qute.property-not-found-strategy"),
                "a per-profile override would reintroduce dev and prod rendering differently");
    }
}
