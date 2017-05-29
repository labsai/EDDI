package ai.labs.parser.rest.impl;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.IInputParser;
import ai.labs.parser.InputParserTask;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.rest.IRestSemanticParser;
import ai.labs.parser.rest.model.Solution;
import ai.labs.resources.rest.parser.IRestParserStore;
import ai.labs.resources.rest.parser.model.ParserConfiguration;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.container.AsyncResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

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
                List<Solution> solutionExpressions = inputParserTask.extractExpressions(rawSolutions);
                asyncResponse.resume(solutionExpressions);
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
            parserTask.configure(parserConfiguration.getConfig());
            parserTask.setExtensions(parserConfiguration.getExtensions());
            parserTask.init();
            cache.put(resourceUri, (InputParserTask) parserTask);
        }
    }

    private ParserConfiguration fetchParserConfiguration(URI resourceUri) throws ServiceException {
        return resourceClientLibrary.getResource(resourceUri, ParserConfiguration.class);
    }
}
