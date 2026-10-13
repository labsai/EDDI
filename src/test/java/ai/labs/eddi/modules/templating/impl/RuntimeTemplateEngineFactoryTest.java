/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateEngineException;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import io.quarkus.qute.Engine;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.StrEvalNamespaceResolver;
import io.quarkus.qute.Results;
import io.quarkus.qute.ValueResolver;
import io.quarkus.qute.ValueResolvers;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restricted runtime-template engine, built exactly as production builds
 * it: {@link TemplatingEngine} hands the source engine to
 * {@link RuntimeTemplateEngineFactory#build}.
 * <p>
 * The source engine here reproduces what the Quarkus-managed engine carries — a
 * {@code config:} namespace backed by the real MicroProfile config (which
 * includes the process environment), {@code inject:}/{@code cdi:}, Qute's
 * {@code str:eval} and an unrestricted {@link ReflectionValueResolver} — plus
 * stand-ins for the resolvers EDDI's own template extensions generate. Each
 * test proves the dangerous feature is present in the source (so the test
 * cannot pass vacuously) and absent from what EDDI renders with.
 */
@DisplayName("Runtime template engine")
class RuntimeTemplateEngineFactoryTest {

    /** An environment variable present on every CI and developer machine. */
    private static final String ENV_KEY = System.getenv("PATH") != null ? "PATH" : "Path";

    /** A bean that {@code inject:} would hand out. */
    public static final class SensitiveBean {
        public String getSecret() {
            return "bean-secret";
        }
    }

    /** A plain POJO in the data model. */
    public static final class Item {
        private final String text;

        Item(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public boolean isShort() {
            return text.length() < 5;
        }

        public String shout(String suffix) {
            return text.toUpperCase() + suffix;
        }
    }

    public record Entry(String speaker, String content) {
    }

    /** A getter whose name merely starts with "getAnd" is still a getter. */
    public static final class Star {
        public String getAndromeda() {
            return "galaxy";
        }
    }

    /** Mirrors the Quarkus-managed engine closely enough to prove the filtering. */
    static Engine quarkusLikeSource() {
        return Engine.builder().addDefaults().strictRendering(false)
                .addValueResolver(new ReflectionValueResolver())
                .addValueResolver(ValueResolvers.rawResolver())
                // stand-in for Quarkus's generated str: helpers (concat, join, ...)
                .addNamespaceResolver(NamespaceResolver.builder("str")
                        .resolve(ctx -> "concat".equals(ctx.getName()) ? "concat-ok" : Results.NotFound.from(ctx)).build())
                // stand-in for a generated EDDI string extension (StringTemplateExtensions)
                .addValueResolver(ValueResolver.builder().appliesTo(ctx -> ctx.getBase() instanceof String && "toUpperCase".equals(ctx.getName()))
                        .resolveSync(ctx -> ((String) ctx.getBase()).toUpperCase()).build())
                // Quarkus's ConfigTemplateExtensions: any MicroProfile config value
                .addNamespaceResolver(NamespaceResolver.builder("config")
                        .resolve(ctx -> ConfigProvider.getConfig().getOptionalValue(ctx.getName(), String.class).orElse("")).build())
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(ctx -> new SensitiveBean()).build())
                .addNamespaceResolver(NamespaceResolver.builder("cdi").resolve(ctx -> new SensitiveBean()).build())
                .addNamespaceResolver(new StrEvalNamespaceResolver())
                // stand-in for EDDI's json: extension
                .addNamespaceResolver(NamespaceResolver.builder("json").resolve(ctx -> "json-ok").build())
                .addNamespaceResolver(new ConfigReferenceNamespaceResolvers.Vault())
                .addNamespaceResolver(new CallerNamespaceResolver())
                .build();
    }

    private static String render(String template, Map<String, Object> data) throws TemplateEngineException {
        return new TemplatingEngine(quarkusLikeSource()).processTemplate(template, data);
    }

    private static String renderSource(String template, Map<String, Object> data) {
        return quarkusLikeSource().parse(template).render(data);
    }

    private static Settings withLimits(int maxOutputChars, int maxIterations) {
        var defaults = Settings.defaults();
        return new Settings(maxOutputChars, maxIterations, defaults.strictRendering(), defaults.removeStandaloneLines(),
                defaults.iterationMetadataPrefix(), defaults.timeoutMillis());
    }

    @Nested
    @DisplayName("namespaces outside the allow-list do not exist")
    class Namespaces {

        @Test
        @DisplayName("config: cannot read configuration or the environment")
        void configNamespaceDoesNotResolve() throws Exception {
            String template = "[{config:" + ENV_KEY + "}]";
            assertNotEquals("[]", renderSource(template, Map.of()), "precondition: the source engine does read the environment");

            assertEquals("[]", render(template, Map.of()));
        }

        @Test
        @DisplayName("config: with bracket syntax does not resolve either")
        void configNamespaceBracketSyntax() throws Exception {
            String template = "[{config:['" + ENV_KEY + "']}]";
            assertEquals("[]", render(template, Map.of()));
        }

        @Test
        @DisplayName("inject: and cdi: cannot reach beans")
        void injectAndCdiDoNotResolve() throws Exception {
            assertEquals("bean-secret", renderSource("{inject:bean.secret}", Map.of()), "precondition");

            assertEquals("[][]", render("[{inject:bean.secret}][{cdi:bean.secret}]", Map.of()));
        }

        @Test
        @DisplayName("str:eval cannot render a value as a template")
        void strEvalDoesNotResolve() throws Exception {
            Map<String, Object> data = Map.of("payload", "{config:" + ENV_KEY + "}");
            assertNotEquals("[]", renderSource("[{str:eval(payload)}]", data), "precondition: str:eval renders data as a template");

            assertEquals("[]", render("[{str:eval(payload)}]", data));
        }

        @Test
        @DisplayName("allow-listed namespaces keep working")
        void allowListedNamespacesResolve() throws Exception {
            assertEquals("json-ok concat-ok ${vault:api-key} ${caller:token}",
                    render("{json:anything} {str:concat} ${vault:api-key} ${caller:token}", Map.of()));
        }

        @Test
        @DisplayName("the allow-list names exactly the namespaces EDDI documents")
        void allowList() {
            assertTrue(RuntimeTemplateEngineFactory.ALLOWED_NAMESPACES.containsAll(
                    List.of("vault", "eddivault", "connection", "vars", "caller", "uuidUtils", "json", "encoder")));
            assertFalse(RuntimeTemplateEngineFactory.ALLOWED_NAMESPACES.contains("config"));
            assertFalse(RuntimeTemplateEngineFactory.ALLOWED_NAMESPACES.contains("inject"));
            assertFalse(RuntimeTemplateEngineFactory.ALLOWED_NAMESPACES.contains("cdi"));
        }
    }

    @Nested
    @DisplayName("sections")
    class Sections {

        @Test
        @DisplayName("{#eval} is not available")
        void evalSectionIsRejected() {
            assertThrows(TemplateEngineException.class, () -> render("{#eval payload /}", Map.of("payload", "{x}")));
        }

        @Test
        @DisplayName("{#include} and {#insert} are not available")
        void includeIsRejected() {
            assertThrows(TemplateEngineException.class, () -> render("{#include other /}", Map.of()));
        }
    }

    @Nested
    @DisplayName("reflection is limited to reading properties")
    class Reflection {

        @Test
        @DisplayName("methods with arguments are not invoked")
        void methodsWithArgumentsAreNotInvoked() throws Exception {
            Map<String, Object> data = Map.of("s", "ab", "item", new Item("hi"));
            assertEquals("ababab", renderSource("{s.repeat(3)}", data), "precondition: the stock resolver invokes it");

            assertEquals("[][]", render("[{s.repeat(3)}][{item.shout('!')}]", data));
        }

        @Test
        @DisplayName("non-getter no-argument methods are not invoked, so data cannot be mutated")
        void mutatorsAreNotInvoked() throws Exception {
            List<String> probe = new ArrayList<>(List.of("a", "b"));
            renderSource("{list.removeFirst}", Map.of("list", probe));
            assertEquals(List.of("b"), probe, "precondition: the stock resolver invokes a mutator");

            List<String> list = new ArrayList<>(List.of("a", "b"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", list);

            render("{list.removeFirst}", data);

            assertEquals(List.of("a", "b"), list);
        }

        @Test
        @DisplayName("getAndX read-modify-write methods are not invoked, so counters cannot be advanced")
        void getAndMethodsAreNotInvoked() throws Exception {
            AtomicInteger probe = new AtomicInteger(5);
            renderSource("{counter.getAndIncrement}", Map.of("counter", probe));
            assertEquals(6, probe.get(), "precondition: the stock resolver invokes getAndIncrement");

            AtomicInteger counter = new AtomicInteger(5);
            Map<String, Object> data = Map.of("counter", counter, "star", new Star());

            assertEquals("[][][][]5 galaxy galaxy",
                    render("[{counter.getAndIncrement}][{counter.andIncrement}][{counter.getAndDecrement}][{counter.andDecrement}]"
                            + "{counter.plain} {star.andromeda} {star.getAndromeda}", data));
            assertEquals(5, counter.get());
        }

        @Test
        @DisplayName("getClass and class loaders are unreachable")
        void classIsUnreachable() throws Exception {
            Map<String, Object> data = Map.of("item", new Item("hi"));
            assertNotEquals("[]", renderSource("[{item.class.name}]", data), "precondition");

            assertEquals("[][][]", render("[{item.class.name}][{item.getClass}][{item.class.classLoader}]", data));
        }

        @Test
        @DisplayName("getters, boolean getters and record components still resolve")
        void propertiesResolve() throws Exception {
            Map<String, Object> data = Map.of("item", new Item("hi"), "entry", new Entry("Pro", "We ship."));

            assertEquals("hi true hi Pro: We ship.", render("{item.text} {item.short} {item.getText} {entry.speaker}: {entry.content}", data));
        }
    }

    @Nested
    @DisplayName("features agent configurations rely on keep working")
    class Features {

        @Test
        void elvisDefault() throws Exception {
            assertEquals("unknown|en", render("{properties.missing ?: 'unknown'}|{properties.lang ?: 'unknown'}",
                    Map.of("properties", Map.of("lang", "en"))));
        }

        @Test
        void loopsWithMetadata() throws Exception {
            assertEquals("1:a,2:b,", render("{#for item in items}{item_count}:{item},{/for}", Map.of("items", List.of("a", "b"))));
            assertEquals("0a1b", render("{#each items}{it_index}{it}{/each}", Map.of("items", List.of("a", "b"))));
        }

        @Test
        void conditionals() throws Exception {
            assertEquals("yes", render("{#if flag}yes{#else}no{/if}", Map.of("flag", true)));
            assertEquals("no", render("{#if flag}yes{#else}no{/if}", Map.of("flag", false)));
            assertEquals("big", render("{#if n > 3}big{/if}", Map.of("n", 5)));
        }

        @Test
        void letWithAndWhen() throws Exception {
            assertEquals("Hi Bob", render("{#let greeting='Hi'}{greeting} {name}{/let}", Map.of("name", "Bob")));
            assertEquals("Bob", render("{#with person}{name}{/with}", Map.of("person", Map.of("name", "Bob"))));
            assertEquals("two", render("{#when n}{#is 1}one{#is 2}two{/when}", Map.of("n", 2)));
        }

        @Test
        void collectionsAndMaps() throws Exception {
            Map<String, Object> data = Map.of("items", List.of("a", "b", "c"), "m", Map.of("k", "v"));
            assertEquals("3 false v", render("{items.size} {items.isEmpty} {m.k}", data));
            assertEquals("3", render("{items.size()}", data));
        }

        @Test
        void rawUnparsedAndComments() throws Exception {
            assertEquals("<b> {literal} x", render("{html.raw} {|{literal}|} {! a comment !}x", Map.of("html", "<b>")));
        }

        @Test
        void generatedExtensionResolversAreCarriedOver() throws Exception {
            assertEquals("ADA", render("{name.toUpperCase()}", Map.of("name", "ada")));
        }

        @Test
        @DisplayName("a missing value renders as nothing, never NOT_FOUND — even without the Quarkus strategy")
        void missingRendersEmpty() throws Exception {
            assertTrue(renderSource("[{properties.missing}]", Map.of("properties", Map.of())).contains("NOT_FOUND"),
                    "precondition: the source engine has no NOOP mapper");

            assertEquals("[]", render("[{properties.missing}]", Map.of("properties", Map.of())));
            assertEquals("[]", render("[{nothing.at.all}]", Map.of()));
        }
    }

    @Nested
    @DisplayName("render limits")
    class Limits {

        @Test
        @DisplayName("output beyond the character limit fails the render")
        void outputLimit() {
            var engine = new TemplatingEngine(quarkusLikeSource(), withLimits(100, 1000));
            var e = assertThrows(TemplateEngineException.class, () -> engine.processTemplate("{#for i in 50}xxxxx{/for}", Map.of()));
            assertTrue(e.getMessage().contains("eddi.templating.max-output-chars"), e.getMessage());
        }

        @Test
        @DisplayName("output within the limit renders")
        void outputWithinLimit() throws Exception {
            var engine = new TemplatingEngine(quarkusLikeSource(), withLimits(100, 1000));
            assertEquals("x".repeat(100), engine.processTemplate("{#for i in 20}xxxxx{/for}", Map.of()));
        }

        @Test
        @DisplayName("an oversized intermediate value fails the render")
        void intermediateValueLimit() {
            var engine = new TemplatingEngine(quarkusLikeSource(), withLimits(100, 1000));
            var e = assertThrows(TemplateEngineException.class,
                    () -> engine.processTemplate("{#if big}ok{/if}", Map.of("big", "y".repeat(500))));
            assertTrue(e.getMessage().contains("eddi.templating.max-output-chars"), e.getMessage());
        }

        @Test
        @DisplayName("a huge integer range is rejected before anything is allocated")
        void hugeRangeIsRejected() {
            var engine = new TemplatingEngine(quarkusLikeSource());
            var e = assertThrows(TemplateEngineException.class,
                    () -> engine.processTemplate("{#for i in n}{/for}", Map.of("n", Integer.MAX_VALUE)));
            assertTrue(e.getMessage().contains("eddi.templating.max-iterations"), e.getMessage());
        }

        @Test
        @DisplayName("nested loops count against one budget per render")
        void nestedLoopsShareTheBudget() throws Exception {
            var engine = new TemplatingEngine(quarkusLikeSource(), withLimits(0, 100));
            assertEquals(".".repeat(25), engine.processTemplate("{#for a in 5}{#for b in 5}.{/for}{/for}", Map.of()));

            var e = assertThrows(TemplateEngineException.class,
                    () -> engine.processTemplate("{#for a in 20}{#for b in 20}.{/for}{/for}", Map.of()));
            assertTrue(e.getMessage().contains("eddi.templating.max-iterations"), e.getMessage());
        }

        @Test
        @DisplayName("the budget is per render, not per engine")
        void budgetIsPerRender() throws Exception {
            var engine = new TemplatingEngine(quarkusLikeSource(), withLimits(0, 100));
            for (int i = 0; i < 5; i++) {
                assertEquals(".".repeat(80), engine.processTemplate("{#for a in 80}.{/for}", Map.of()));
            }
        }
    }

    @Nested
    @DisplayName("decimals render as plain numbers")
    class Decimals {

        @Test
        @DisplayName("a Double or Float that toString() would print in E-notation renders in plain digits")
        void scientificNotationIsAvoided() throws Exception {
            assertEquals("1.0E7|1.0E-4", renderSource("{a}|{b}", Map.of("a", 1.0e7, "b", 0.0001)),
                    "precondition: Qute prints toString()");

            assertEquals("10000000|0.0001|12500000.5|1759400000000|0.00025",
                    render("{a}|{b}|{c}|{d}|{e}", Map.of("a", 1.0e7, "b", 0.0001, "c", 12_500_000.5, "d", 1.7594e12, "e", 2.5e-4f)));
        }

        @Test
        @DisplayName("ordinary decimals, extreme magnitudes, NaN and integers are unchanged")
        void otherValuesUnchanged() throws Exception {
            assertEquals("19.99|48.2081743|3.0|1.0E21|1.0E-8|NaN|Infinity|3000000000",
                    render("{a}|{b}|{c}|{d}|{e}|{f}|{g}|{h}", Map.of("a", 19.99, "b", 48.2081743, "c", 3.0, "d", 1e21, "e", 1e-8, "f",
                            Double.NaN, "g", Double.POSITIVE_INFINITY, "h", 3_000_000_000L)));
        }
    }
}
