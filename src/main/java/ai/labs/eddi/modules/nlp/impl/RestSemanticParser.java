/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.IRestSemanticParser;
import ai.labs.eddi.modules.nlp.Solution;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.modules.nlp.DictionaryUtilities.extractExpressions;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestSemanticParser implements IRestSemanticParser {

    /**
     * Upper bound on the number of distinct parser configurations kept in memory.
     * The cache key is derived from a caller-supplied config id, so the cache must
     * be bounded — otherwise a careless or malicious caller could grow it without
     * limit.
     */
    static final int MAX_CACHED_PARSERS = 100;

    /**
     * How long a built parser is retained after it was put into the cache.
     * <p>
     * It is deliberately <em>not</em> what makes an edited configuration visible:
     * the cache key is the versioned resource URI ({@code parserId} +
     * {@code version}) and the parser store is historized — updating a parser
     * configuration writes version+1, so an edit is served under a different key
     * from the moment it is saved. What the TTL bounds is retention: a parser built
     * from a version that has since been superseded or deleted eventually stops
     * occupying a cache slot instead of living until the process restarts.
     */
    static final Duration PARSER_CACHE_TTL = Duration.ofMinutes(5);

    private final IRuntime runtime;
    private final IResourceClientLibrary resourceClientLibrary;
    private final Provider<ILifecycleTask> parserProvider;

    /**
     * Bounded, thread-safe parser cache. This bean is an {@code @ApplicationScoped}
     * singleton and parsers are created on runtime pool threads, so the cache is
     * mutated concurrently. Caffeine applies the loader at most once per key, which
     * guarantees that concurrent requests for the same, not-yet-cached parser
     * configuration create exactly one parser instance.
     */
    private final Cache<URI, IInputParser> parserCache;

    private final Logger log = Logger.getLogger(RestSemanticParser.class);

    @Inject
    public RestSemanticParser(IRuntime runtime, IResourceClientLibrary resourceClientLibrary,
            @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTasks) {
        this(runtime, resourceClientLibrary, lifecycleTasks, Ticker.systemTicker());
    }

    /**
     * Test seam: the same construction with an injectable clock, so cache expiry
     * can be driven deterministically instead of by waiting
     * {@link #PARSER_CACHE_TTL}.
     */
    RestSemanticParser(IRuntime runtime, IResourceClientLibrary resourceClientLibrary,
            Map<String, Provider<ILifecycleTask>> lifecycleTasks, Ticker ticker) {
        this.runtime = runtime;
        this.resourceClientLibrary = resourceClientLibrary;
        this.parserProvider = lifecycleTasks.get("ai.labs.parser");

        this.parserCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_PARSERS)
                .expireAfterWrite(PARSER_CACHE_TTL)
                .ticker(ticker)
                .build();
    }

    @Override
    public void parse(String configId, Integer version, String sentence, AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(30, TimeUnit.SECONDS);

        runtime.submitCallable((Callable<Void>) () -> {
            try {
                URI resourceUri = URI.create(IRestParserStore.resourceURI + configId + IRestParserStore.versionQueryParam + version);
                IInputParser inputParser = getParser(resourceUri);
                List<RawSolution> rawSolutions = inputParser.parse(sentence);
                List<Solution> solutionExpressions = extractExpressions(rawSolutions, true, true);
                asyncResponse.resume(solutionExpressions.stream().map(solution -> new ResponseSolution(solution.getExpressions())).toList());
            } catch (IllegalArgumentException e) {
                asyncResponse.resume(new BadRequestException(e.getLocalizedMessage()));
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                asyncResponse.resume(new InternalServerErrorException());
            }

            return null;
        }, null);
    }

    private IInputParser getParser(URI resourceUri) throws Exception {
        try {
            return parserCache.get(resourceUri, this::createParser);
        } catch (ParserCreationException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * Cache loader. Runs at most once per key, even when several pool threads
     * request the same parser configuration simultaneously.
     */
    private IInputParser createParser(URI resourceUri) {
        try {
            ILifecycleTask parserTask = parserProvider.get();
            var parserConfiguration = fetchParserConfiguration(resourceUri);
            var config = parserConfiguration.getConfig();
            var extensions = parserConfiguration.getExtensions();
            return (IInputParser) parserTask.configure(config != null ? config : new HashMap<>(),
                    extensions != null ? extensions : new HashMap<>());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserCreationException(e);
        }
    }

    /**
     * Drops all cached parsers so that the next request re-reads the parser
     * configuration from the store.
     * <p>
     * Not public, and deliberately not wired into the parser store: that store is
     * historized, so an update writes a new version and therefore a new cache key —
     * there is nothing for an update hook to invalidate. This exists so the cache
     * tests can assert that cached parsers really are rebuilt from the store, not
     * as a configuration-update contract.
     */
    void invalidateCache() {
        parserCache.invalidateAll();
    }

    /**
     * Number of parsers currently cached, after running pending cache maintenance.
     * Never exceeds {@link #MAX_CACHED_PARSERS}.
     */
    long cachedParserCount() {
        parserCache.cleanUp();
        return parserCache.estimatedSize();
    }

    private ParserConfiguration fetchParserConfiguration(URI resourceUri) throws ServiceException {
        return resourceClientLibrary.getResource(resourceUri, ParserConfiguration.class);
    }

    /**
     * Carries a checked exception out of the cache loader, which cannot declare
     * checked exceptions. Unwrapped again in {@link #getParser(URI)} so callers
     * keep seeing the original exception type.
     */
    private static final class ParserCreationException extends RuntimeException {
        private ParserCreationException(Exception cause) {
            super(cause);
        }
    }

    public static class ResponseSolution {
        private String expressions;

        public ResponseSolution(Expressions exps) {
            expressions = exps.toString();
        }

        public ResponseSolution() {
        }

        public String getExpressions() {
            return expressions;
        }

        public void setExpressions(String expressions) {
            this.expressions = expressions;
        }
    }
}
