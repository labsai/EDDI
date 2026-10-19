/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateEngineException;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import io.quarkus.qute.Engine;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.Results;
import io.quarkus.qute.ValueResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10-17, M10: {@code str:fmt} allocated before the output
 * cap was checked, the render timeout never fired for a synchronous render, and
 * deep section nesting escaped {@code processTemplate} as a raw
 * {@code StackOverflowError}.
 */
@DisplayName("TemplatingEngine — str:fmt pre-check, render deadline, nesting depth")
class TemplatingEngineResourceLimitsTest {

    /**
     * Stand-ins for Quarkus's generated {@code StringTemplateExtensions}: an
     * unchecked {@code String.format}, exactly what {@code str:fmt} and
     * {@code String.fmt} are. If the bounded resolvers did not take precedence, the
     * 2 GB payloads below would be allocated here.
     */
    private static Engine sourceWithUncheckedFmt() {
        return Engine.builder().addDefaults().strictRendering(false)
                .addNamespaceResolver(NamespaceResolver.builder("str").resolveAsync(ctx -> {
                    if (!"fmt".equals(ctx.getName()) && !"format".equals(ctx.getName())) {
                        return Results.notFound(ctx);
                    }
                    return evaluate(ctx).thenApply(values -> (Object) String.format((String) values.getFirst(),
                            values.subList(1, values.size()).toArray()));
                }).build())
                .addValueResolver(ValueResolver.builder()
                        .appliesTo(ctx -> ctx.getBase() instanceof String && "fmt".equals(ctx.getName()))
                        .resolveAsync(ctx -> evaluate(ctx).thenApply(values -> (Object) String.format((String) ctx.getBase(), values.toArray())))
                        .build())
                .build();
    }

    private static CompletionStage<List<Object>> evaluate(EvalContext ctx) {
        CompletableFuture<List<Object>> all = CompletableFuture.completedFuture(new ArrayList<>());
        for (var param : ctx.getParams()) {
            all = all.thenCompose(list -> ctx.evaluate(param).thenApply(value -> {
                list.add(value);
                return list;
            }));
        }
        return all;
    }

    private static Settings settings(int maxOutputChars, long timeoutMillis) {
        var defaults = Settings.defaults();
        return new Settings(maxOutputChars, defaults.maxIterations(), defaults.strictRendering(), defaults.removeStandaloneLines(),
                defaults.iterationMetadataPrefix(), timeoutMillis);
    }

    private static TemplatingEngine engine() {
        return new TemplatingEngine(sourceWithUncheckedFmt(), Settings.defaults());
    }

    @Nested
    @DisplayName("str:fmt and String.fmt")
    class Format {

        @Test
        @DisplayName("a 2 GB width is refused before anything is allocated")
        void hugeWidthRefusedFast() {
            long start = System.nanoTime();
            var e = assertThrows(TemplateEngineException.class, () -> engine().processTemplate("{str:fmt('%2000000000d', 1)}", Map.of()));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(e.getMessage().contains("str:fmt would produce"), e.getMessage());
            assertTrue(elapsedMs < 2_000, "refused in milliseconds, took " + elapsedMs + "ms");
        }

        @Test
        @DisplayName("the instance form, a huge precision and a format string repeating a long argument are refused too")
        void otherShapesRefused() {
            var engine = engine();
            assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{f.fmt(1)}", Map.of("f", "%2000000000d")));
            assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{str:fmt('%.1999999999f', x)}", Map.of("x", 1.5)));
            assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{str:fmt('%1900000d%1900000d', 1, 2)}", Map.of()));
            String longArg = "x".repeat(1_500_000);
            assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{str:fmt('%s%1$s', s)}", Map.of("s", longArg)));
        }

        @Test
        @DisplayName("ordinary formatting still works, with and without a locale")
        void ordinaryFormatting() throws Exception {
            var engine = engine();
            // Qute has no plain decimal literal, so the numbers come from the data
            assertEquals("03.14", engine.processTemplate("{str:fmt(loc, '%05.2f', pi)}", Map.of("loc", Locale.ROOT, "pi", 3.14159)));
            assertEquals("03.14", engine.processTemplate("{f.fmt(loc, pi)}", Map.of("f", "%05.2f", "loc", Locale.ROOT, "pi", 3.14159)));
            assertEquals("a-b", engine.processTemplate("{str:format('%s-%s', 'a', 'b')}", Map.of()));
            assertEquals("007", engine.processTemplate("{f.fmt(7)}", Map.of("f", "%03d")));
            assertEquals("1.234,50", engine.processTemplate("{str:fmt(loc, '%,.2f', n)}", Map.of("loc", Locale.GERMANY, "n", 1234.5)));
            assertEquals("100%", engine.processTemplate("{str:fmt('%d%%', 100)}", Map.of()));
        }

        @Test
        @DisplayName("the estimate is an upper bound and saturates instead of overflowing")
        void estimate() {
            // an upper bound: a number is allowed for its widest rendering, a width beyond
            // it wins
            assertTrue(BoundedFormat.estimate("%5d", new Object[]{1}, Long.MAX_VALUE) >= 5);
            assertEquals(1000, BoundedFormat.estimate("%1000d", new Object[]{1}, Long.MAX_VALUE));
            assertEquals(3, BoundedFormat.estimate("ab%%", new Object[]{}, Long.MAX_VALUE));
            assertEquals(Long.MAX_VALUE, BoundedFormat.estimate("%99999999999999999999d", new Object[]{1}, Long.MAX_VALUE));
            assertEquals(5, BoundedFormat.estimate("%s-%<s", new Object[]{"ab"}, Long.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("render deadline")
    class Deadline {

        @Test
        @DisplayName("a CPU-bound loop under every cap stops at the timeout")
        void loopStopsAtTimeout() {
            var engine = new TemplatingEngine(sourceWithUncheckedFmt(), settings(2_000_000, 300));
            long start = System.nanoTime();
            var e = assertThrows(TemplateEngineException.class,
                    () -> engine.processTemplate("{#for i in 99999}{#let x=str:fmt('%1900000d',1)}{/let}{/for}", Map.of()));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(e.getMessage().contains("exceeded its timeout of 300 ms"), e.getMessage());
            assertTrue(elapsedMs < 10_000, "stopped near the 300 ms timeout, took " + elapsedMs + "ms");
        }

        @Test
        @DisplayName("an ordinary render finishes well within the deadline")
        void ordinaryRenderUnaffected() throws Exception {
            var engine = new TemplatingEngine(sourceWithUncheckedFmt(), settings(2_000_000, 300));
            assertEquals("012", engine.processTemplate("{#for i in 3}{i_index}{/for}", Map.of()));
        }
    }

    @Nested
    @DisplayName("nesting depth")
    class Nesting {

        @Test
        @DisplayName("thousands of nested {#if}s are a template error, never a raw StackOverflowError")
        void deepNestingIsATemplateError() throws Exception {
            String template = "{#if true}".repeat(5_000) + "x" + "{/if}".repeat(5_000);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            // A small stack makes the overflow certain on any JVM configuration.
            Thread renderer = new Thread(null, () -> {
                try {
                    new TemplatingEngine(sourceWithUncheckedFmt()).processTemplate(template, Map.of());
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, "deep-nesting", 256 * 1024);
            renderer.start();
            renderer.join(60_000);

            assertNotNull(thrown.get(), "the 256 kB stack must overflow on 5,000 nested sections");
            assertInstanceOf(TemplateEngineException.class, thrown.get());
            assertTrue(thrown.get().getMessage().contains("too deeply"), thrown.get().getMessage());
        }
    }
}
