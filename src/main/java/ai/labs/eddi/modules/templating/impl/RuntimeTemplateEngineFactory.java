/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.EvalContext;
import io.quarkus.qute.Expression;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.ResultMapper;
import io.quarkus.qute.Results;
import io.quarkus.qute.SetSectionHelper;
import io.quarkus.qute.StrEvalNamespaceResolver;
import io.quarkus.qute.TemplateNode.Origin;
import io.quarkus.qute.ValueResolver;
import io.quarkus.qute.WhenSectionHelper;
import io.quarkus.qute.WithSectionHelper;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Builds the Qute {@link Engine} EDDI renders its <em>runtime</em> templates
 * with — system prompts, output texts, httpcall bodies, property instructions.
 * <p>
 * Those template strings are parsed at runtime from agent configuration, which
 * makes the engine's feature set part of EDDI's security boundary. The engine
 * Quarkus injects is built for the application's own, build-time-validated
 * templates and carries features that must not be reachable from agent
 * configuration:
 * <ul>
 * <li>the {@code config:} namespace, which reads any MicroProfile config value
 * — environment variables and secrets included;</li>
 * <li>the {@code inject:} / {@code cdi:} namespaces, which hand a template any
 * named CDI bean;</li>
 * <li>{@code str:eval} and the {@code {#eval}} section, which parse a
 * <em>value</em> as a template — second-order rendering;</li>
 * <li>an unrestricted {@link ReflectionValueResolver}, which invokes any public
 * method with arguments on any object a template can reach.</li>
 * </ul>
 * This factory therefore builds a separate engine. It takes over the value and
 * namespace resolvers the Quarkus engine was given at build time — which is the
 * only way to obtain the resolvers Quarkus generates for EDDI's own
 * {@code @TemplateExtension} classes — but only through an explicit allow-list:
 * <ul>
 * <li>namespaces: only {@link #ALLOWED_NAMESPACES}; {@code str:eval} is dropped
 * even though {@code str} is allowed;</li>
 * <li>sections: {@code if}, {@code for}/{@code each}, {@code let}/{@code set},
 * {@code with}, {@code when}/{@code switch} — no {@code include},
 * {@code insert}, {@code eval}, {@code fragment}, {@code cache} or user
 * tags;</li>
 * <li>reflection: replaced by {@link PropertyAccessValueResolver}, which reads
 * properties (getters, record components, public fields) and never invokes a
 * method with arguments.</li>
 * </ul>
 * Every resolver is additionally wrapped by {@link BoundedResolvers} so no
 * intermediate string may exceed the configured output cap, loops are bounded
 * by {@link BoundedLoopSectionHelperFactory}, and a missing value always
 * renders as the empty string (never the literal {@code NOT_FOUND}), whatever
 * {@code quarkus.qute.property-not-found-strategy} says.
 * <p>
 * A plain class with a static builder method rather than a CDI producer, so the
 * exact production construction can be exercised by a plain unit test.
 */
public final class RuntimeTemplateEngineFactory {

    /**
     * Namespaces an agent author may use in a runtime template.
     * <ul>
     * <li>{@code vault}, {@code eddivault}, {@code connection}, {@code vars},
     * {@code caller} — EDDI's pass-through references (they render themselves
     * verbatim, see {@link ReferencePassThroughNamespaceResolver});</li>
     * <li>{@code uuidUtils}, {@code json}, {@code encoder} — EDDI's own template
     * extensions;</li>
     * <li>{@code str}, {@code time} — Qute's string and date formatting helpers
     * (minus {@code str:eval}).</li>
     * </ul>
     * Anything else — notably {@code config}, {@code inject} and {@code cdi} — does
     * not exist for a runtime template, and an expression using it renders empty
     * like any other unresolvable expression.
     */
    public static final Set<String> ALLOWED_NAMESPACES = Set.of(
            "vault", "eddivault", "connection", "vars", CallerNamespaceResolver.NAMESPACE,
            "uuidUtils", "json", "encoder",
            "str", "time");

    /**
     * Namespaces the Quarkus engine offers that are deliberately taken away. They
     * resolve to nothing, so an expression using one renders empty.
     */
    static final Set<String> BLOCKED_NAMESPACES = Set.of("config", "inject", "cdi");

    /**
     * Priority of the result mapper that renders a missing value as nothing. Above
     * any mapper Quarkus registers for its own not-found strategies, so the
     * guarantee does not depend on
     * {@code quarkus.qute.property-not-found-strategy}.
     */
    static final int NOT_FOUND_MAPPER_PRIORITY = 1000;

    private RuntimeTemplateEngineFactory() {
    }

    /**
     * Rendering settings and limits.
     *
     * @param maxOutputChars
     *            upper bound for the rendered output and for any single string an
     *            expression produces while rendering; {@code <= 0} disables it
     * @param maxIterations
     *            upper bound for the loop iterations of one render, summed over all
     *            (nested) loops; {@code <= 0} disables it
     * @param strictRendering
     *            {@code quarkus.qute.strict-rendering}
     * @param removeStandaloneLines
     *            {@code quarkus.qute.remove-standalone-lines}
     * @param iterationMetadataPrefix
     *            {@code quarkus.qute.iteration-metadata-prefix}
     * @param timeoutMillis
     *            {@code quarkus.qute.timeout}
     */
    public record Settings(int maxOutputChars, int maxIterations, boolean strictRendering, boolean removeStandaloneLines,
            String iterationMetadataPrefix, long timeoutMillis) {

        public static final int DEFAULT_MAX_OUTPUT_CHARS = 2_000_000;
        public static final int DEFAULT_MAX_ITERATIONS = 100_000;
        public static final String DEFAULT_ITERATION_METADATA_PREFIX = "<alias_>";
        public static final long DEFAULT_TIMEOUT_MILLIS = 10_000L;

        /** The values EDDI ships with — the same as the Quarkus defaults. */
        public static Settings defaults() {
            return new Settings(DEFAULT_MAX_OUTPUT_CHARS, DEFAULT_MAX_ITERATIONS, false, true, DEFAULT_ITERATION_METADATA_PREFIX,
                    DEFAULT_TIMEOUT_MILLIS);
        }
    }

    /**
     * Builds the restricted runtime-template engine.
     *
     * @param source
     *            the Quarkus-managed engine; only its value resolvers, namespace
     *            resolvers and result mappers are read, and only through the
     *            allow-lists described on the class
     * @param settings
     *            rendering settings and limits
     */
    public static Engine build(Engine source, Settings settings) {
        EngineBuilder builder = Engine.builder()
                .strictRendering(settings.strictRendering())
                .removeStandaloneLines(settings.removeStandaloneLines())
                .iterationMetadataPrefix(settings.iterationMetadataPrefix())
                .timeout(settings.timeoutMillis())
                .addSectionHelper(new IfSectionHelper.Factory())
                .addSectionHelper(new BoundedLoopSectionHelperFactory(settings.iterationMetadataPrefix(), settings.maxIterations()))
                .addSectionHelper(new WithSectionHelper.Factory())
                .addSectionHelper(new SetSectionHelper.Factory())
                .addSectionHelper(new WhenSectionHelper.Factory());

        int maxChars = settings.maxOutputChars();
        for (ValueResolver resolver : source.getValueResolvers()) {
            if (resolver instanceof ReflectionValueResolver) {
                continue;
            }
            builder.addValueResolver(BoundedResolvers.bound(resolver, maxChars));
        }
        builder.addValueResolver(BoundedResolvers.bound(new PropertyAccessValueResolver(), maxChars));

        for (NamespaceResolver resolver : source.getNamespaceResolvers()) {
            if (isAllowed(resolver)) {
                builder.addNamespaceResolver(BoundedResolvers.bound(resolver, maxChars));
            }
        }
        // The removed namespaces are registered as resolving to nothing rather than
        // simply left out: a namespace Qute does not know fails the WHOLE render,
        // which would turn one stray expression in an existing configuration into
        // a broken prompt or reply. Rendering empty is the behaviour of every other
        // unresolvable expression.
        for (String blocked : BLOCKED_NAMESPACES) {
            builder.addNamespaceResolver(new ResolvesToNothing(blocked));
        }

        for (ResultMapper mapper : source.getResultMappers()) {
            builder.addResultMapper(mapper);
        }
        builder.addResultMapper(new NotFoundRendersNothing());
        builder.addResultMapper(new PlainDecimalRendering());

        return builder.build();
    }

    static boolean isAllowed(NamespaceResolver resolver) {
        return ALLOWED_NAMESPACES.contains(resolver.getNamespace()) && !(resolver instanceof StrEvalNamespaceResolver);
    }

    /** A namespace that exists only to resolve every expression to "not found". */
    record ResolvesToNothing(String namespace) implements NamespaceResolver {
        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public CompletionStage<Object> resolve(EvalContext context) {
            return Results.notFound(context);
        }
    }

    /**
     * Same behaviour as Quarkus's {@code PropertyNotFoundNoop}: a value that could
     * not be resolved renders as nothing.
     */
    static final class NotFoundRendersNothing implements ResultMapper {
        @Override
        public int getPriority() {
            return NOT_FOUND_MAPPER_PRIORITY;
        }

        @Override
        public boolean appliesTo(Origin origin, Object result) {
            return Results.isNotFound(result);
        }

        @Override
        public String map(Object result, Expression expression) {
            return "";
        }
    }

    /**
     * Renders a {@code Double} or {@code Float} in plain notation where
     * {@code toString()} would switch to scientific notation — {@code 12500000.5}
     * instead of {@code 1.25000005E7}, {@code 0.0001} instead of {@code 1.0E-4}. A
     * decimal read from an API response or a client context is stored as a
     * {@code Double} property, and {@code {properties.price}} in a reply, a URL or
     * a request body has to read like the number it is. Within the range where
     * JavaScript also prints plain digits ({@code 1e-7 <= |x| < 1e21}); beyond it,
     * and for NaN and the infinities, {@code toString()} is kept.
     */
    static final class PlainDecimalRendering implements ResultMapper {
        @Override
        public int getPriority() {
            return 10;
        }

        @Override
        public boolean appliesTo(Origin origin, Object result) {
            if (result instanceof Double || result instanceof Float) {
                double value = ((Number) result).doubleValue();
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    return false;
                }
                double magnitude = Math.abs(value);
                return result.toString().indexOf('E') >= 0 && magnitude >= 1e-7 && magnitude < 1e21;
            }
            return false;
        }

        @Override
        public String map(Object result, Expression expression) {
            // From toString(), not from the binary value: 0.1 stays 0.1, and a Float keeps
            // its own shortest representation instead of a widened double's digits.
            return new BigDecimal(result.toString()).stripTrailingZeros().toPlainString();
        }
    }

    /**
     * Thrown when a render exceeds one of the configured {@link Settings} limits.
     */
    public static final class TemplateLimitExceededException extends RuntimeException {
        public TemplateLimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * Resolver wrappers that reject oversized string results.
     */
    static final class BoundedResolvers {

        private BoundedResolvers() {
        }

        static ValueResolver bound(ValueResolver delegate, int maxChars) {
            return maxChars <= 0 ? delegate : new BoundedValueResolver(delegate, maxChars);
        }

        static NamespaceResolver bound(NamespaceResolver delegate, int maxChars) {
            return maxChars <= 0 ? delegate : new BoundedNamespaceResolver(delegate, maxChars);
        }

        static Object check(Object value, int maxChars) {
            if (value instanceof CharSequence chars && chars.length() > maxChars) {
                throw new TemplateLimitExceededException(
                        "A template expression produced " + chars.length() + " characters, more than the limit of " + maxChars
                                + " (eddi.templating.max-output-chars)");
            }
            return value;
        }

        /**
         * Deliberately does NOT delegate {@code getCachedResolver}: the default returns
         * {@code this}, which keeps the bound in place for cached lookups.
         */
        private record BoundedValueResolver(ValueResolver delegate, int maxChars) implements ValueResolver {
            @Override
            public int getPriority() {
                return delegate.getPriority();
            }

            @Override
            public boolean appliesTo(EvalContext context) {
                return delegate.appliesTo(context);
            }

            @Override
            public CompletionStage<Object> resolve(EvalContext context) {
                return delegate.resolve(context).thenApply(value -> check(value, maxChars));
            }

            @Override
            public Set<String> getSupportedProperties() {
                return delegate.getSupportedProperties();
            }

            @Override
            public Set<String> getSupportedMethods() {
                return delegate.getSupportedMethods();
            }
        }

        private record BoundedNamespaceResolver(NamespaceResolver delegate, int maxChars) implements NamespaceResolver {
            @Override
            public String getNamespace() {
                return delegate.getNamespace();
            }

            @Override
            public int getPriority() {
                return delegate.getPriority();
            }

            @Override
            public CompletionStage<Object> resolve(EvalContext context) {
                return delegate.resolve(context).thenApply(value -> check(value, maxChars));
            }
        }
    }
}
