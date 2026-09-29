/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.TemplateLimitExceededException;
import io.quarkus.qute.Expression;
import io.quarkus.qute.LoopSectionHelper;
import io.quarkus.qute.ResolutionContext;
import io.quarkus.qute.ResultNode;
import io.quarkus.qute.Scope;
import io.quarkus.qute.SectionBlock;
import io.quarkus.qute.SectionHelper;
import io.quarkus.qute.SectionHelper.SectionResolutionContext;
import io.quarkus.qute.SectionHelperFactory;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Qute's {@code {#for}} / {@code {#each}} section with an iteration bound.
 * <p>
 * Two things are bounded, both against the same per-render budget:
 * <ul>
 * <li>the size of what a loop is about to iterate. Qute pre-sizes its result
 * list from that size, so an integer range such as {@code {#for i in n}} with a
 * huge {@code n} would allocate before a single iteration ran — the check must
 * happen before the stock helper sees the value;</li>
 * <li>the total number of iterations of one render, summed over all loops, so
 * nesting cannot multiply its way past the bound.</li>
 * </ul>
 * Everything else — parsing, aliases, iteration metadata, the {@code else}
 * block — is the stock {@link LoopSectionHelper}.
 */
final class BoundedLoopSectionHelperFactory implements SectionHelperFactory<SectionHelper> {

    /** Template-instance attribute carrying the per-render {@link AtomicLong}. */
    static final String ITERATION_BUDGET_ATTRIBUTE = "eddi.templating.iterations";

    private static final String ITERABLE = "iterable";

    private final LoopSectionHelper.Factory delegate;
    private final long maxIterations;

    BoundedLoopSectionHelperFactory(String iterationMetadataPrefix, int maxIterations) {
        this.delegate = new LoopSectionHelper.Factory(iterationMetadataPrefix);
        this.maxIterations = maxIterations;
    }

    @Override
    public List<String> getDefaultAliases() {
        return delegate.getDefaultAliases();
    }

    @Override
    public ParametersInfo getParameters() {
        return delegate.getParameters();
    }

    @Override
    public List<String> getBlockLabels() {
        return delegate.getBlockLabels();
    }

    @Override
    public boolean cacheFactoryConfig() {
        return delegate.cacheFactoryConfig();
    }

    @Override
    public boolean treatUnknownSectionsAsBlocks() {
        return delegate.treatUnknownSectionsAsBlocks();
    }

    @Override
    public MissingEndTagStrategy missingEndTagStrategy() {
        return delegate.missingEndTagStrategy();
    }

    @Override
    public Scope initializeBlock(Scope previousScope, BlockInfo block) {
        return delegate.initializeBlock(previousScope, block);
    }

    @Override
    public SectionHelper initialize(SectionInitContext context) {
        LoopSectionHelper loop = delegate.initialize(context);
        if (maxIterations <= 0) {
            return loop;
        }
        return new BoundedLoop(loop, context.getExpression(ITERABLE), maxIterations);
    }

    private record BoundedLoop(LoopSectionHelper loop, Expression iterable, long maxIterations) implements SectionHelper {

        @Override
        public CompletionStage<ResultNode> resolve(SectionResolutionContext context) {
            AtomicLong budget = budgetOf(context.resolutionContext());
            // Evaluated here to be checked before the stock helper sizes anything
            // from it; the helper then evaluates the (side-effect free) expression
            // again itself.
            return context.resolutionContext().evaluate(iterable).thenCompose(value -> {
                long size = sizeOf(value);
                long used = budget.get();
                if (size > maxIterations || used + size > maxIterations) {
                    return CompletableFuture.failedFuture(exceeded());
                }
                return loop.resolve(new CountingContext(context, budget, maxIterations));
            });
        }

        private TemplateLimitExceededException exceeded() {
            return BoundedLoopSectionHelperFactory.exceeded(maxIterations);
        }
    }

    static TemplateLimitExceededException exceeded(long maxIterations) {
        return new TemplateLimitExceededException(
                "A template loop exceeded the limit of " + maxIterations + " iterations per render (eddi.templating.max-iterations)");
    }

    private static AtomicLong budgetOf(ResolutionContext resolutionContext) {
        Object attribute = resolutionContext.getAttribute(ITERATION_BUDGET_ATTRIBUTE);
        // A render that did not go through TemplatingEngine still gets a bound —
        // per loop instead of per render.
        return attribute instanceof AtomicLong budget ? budget : new AtomicLong();
    }

    /**
     * The number of iterations Qute will perform for {@code value}, or 0 for a
     * shape it does not iterate over (it fails on those by itself).
     */
    static long sizeOf(Object value) {
        if (value instanceof Integer integer) {
            return Math.max(0, integer);
        }
        if (value instanceof Long longValue) {
            return Math.max(0, longValue);
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return 0;
    }

    /**
     * Counts every block execution — one per iteration — against the per-render
     * budget. Covers iterables whose size is not known upfront (iterators,
     * streams).
     */
    private record CountingContext(SectionResolutionContext delegate, AtomicLong budget, long maxIterations)
            implements
                SectionResolutionContext {

        @Override
        public CompletionStage<Map<String, Object>> evaluate(Map<String, Expression> parameters) {
            return delegate.evaluate(parameters);
        }

        @Override
        public CompletionStage<Object> evaluate(Expression expression) {
            return delegate.evaluate(expression);
        }

        @Override
        public ResolutionContext resolutionContext() {
            return delegate.resolutionContext();
        }

        @Override
        public ResolutionContext newResolutionContext(Object data, Map<String, SectionBlock> extendingBlocks) {
            return delegate.newResolutionContext(data, extendingBlocks);
        }

        @Override
        public CompletionStage<ResultNode> execute() {
            return delegate.execute();
        }

        @Override
        public CompletionStage<ResultNode> execute(ResolutionContext context) {
            if (budget.incrementAndGet() > maxIterations) {
                return CompletableFuture.failedFuture(exceeded(maxIterations));
            }
            return delegate.execute(context);
        }

        @Override
        public CompletionStage<ResultNode> execute(SectionBlock block, ResolutionContext context) {
            return delegate.execute(block, context);
        }

        @Override
        public Map<String, Object> getParameters() {
            return delegate.getParameters();
        }
    }
}
