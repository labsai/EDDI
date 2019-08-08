package ai.labs.parser.rest.impl;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.expressions.Expressions;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.IInputParser;
import ai.labs.parser.InputParserTask;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.rest.IRestSemanticParser;
import ai.labs.parser.rest.model.Solution;
import ai.labs.resources.rest.config.parser.IRestParserStore;
import ai.labs.resources.rest.config.parser.model.ParserConfiguration;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.container.AsyncResponse;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ai.labs.parser.DictionaryUtilities.extractExpressions;

/**
 * @author ginccc
 */
@Slf4j
public class RestSemanticParser implements IRestSemanticParser {
    private final SystemRuntime.IRuntime runtime;
    private final IResourceClientLibrary resourceClientLibrary;
    private final Provider<ILifecycleTask> parserProvider;
    private final ICache<URI, InputParserTask> cache;

    @Inject
    public RestSemanticParser(SystemRuntime.IRuntime runtime,
                              IResourceClientLibrary resourceClientLibrary,
                              ICacheFactory cacheFactory,
                              Map<String, Provider<ILifecycleTask>> lifecycleTasks) {
        this.runtime = runtime;
        this.resourceClientLibrary = resourceClientLibrary;
        cache = cacheFactory.getCache("ai.labs.parser");
        parserProvider = lifecycleTasks.get("ai.labs.parser");
    }

    @Override
    public void parse(String configId, Integer version, String sentence, AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(30, TimeUnit.SECONDS);

        runtime.submitCallable((Callable<Void>) () -> {
            try {
                URI resourceUri = URI.create(IRestParserStore.resourceURI + configId +
                        IRestParserStore.versionQueryParam + version);
                InputParserTask inputParserTask = getParser(resourceUri);
                IInputParser inputParser = (IInputParser) inputParserTask.getComponent();
                List<RawSolution> rawSolutions = inputParser.parse(sentence);
                List<Solution> solutionExpressions = extractExpressions(rawSolutions, true, true);
                asyncResponse.resume(solutionExpressions.stream().
                        map(solution -> new ResponseSolution(solution.getExpressions())).
                        collect(Collectors.toList()));
            } catch (IllegalArgumentException e) {
                asyncResponse.resume(new BadRequestException(e.getLocalizedMessage()));
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                asyncResponse.resume(new InternalServerErrorException());
            }

            return null;
        }, null);
    }

    private InputParserTask getParser(URI resourceUri) throws Exception {
        createParserIfAbsent(resourceUri);
        return cache.get(resourceUri);
    }

    private void createParserIfAbsent(URI resourceUri) throws Exception {
        if (!cache.containsKey(resourceUri)) {
            ILifecycleTask parserTask = parserProvider.get();
            ParserConfiguration parserConfiguration = fetchParserConfiguration(resourceUri);
            Map<String, Object> config = parserConfiguration.getConfig();
            parserTask.configure(config != null ? config : new HashMap<>());
            Map<String, Object> extensions = parserConfiguration.getExtensions();
            parserTask.setExtensions(extensions != null ? extensions : new HashMap<>());
            parserTask.init();
            cache.put(resourceUri, (InputParserTask) parserTask);
        }
    }

    private ParserConfiguration fetchParserConfiguration(URI resourceUri) throws ServiceException {
        return resourceClientLibrary.getResource(resourceUri, ParserConfiguration.class);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ResponseSolution {
        private String expressions;

        public ResponseSolution(Expressions exps) {
            expressions = exps.toString();
        }
    }
}
