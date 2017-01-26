package ai.labs.parser.rest.impl;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.IInputParser;
import ai.labs.parser.internal.matches.Solution;
import ai.labs.parser.rest.IRestSemanticParser;
import ai.labs.resources.rest.parser.IRestParserStore;
import ai.labs.resources.rest.parser.model.ParserConfiguration;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
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
    private final ICache<String, IInputParser> cache;

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
    public void parse(String configId, String sentence, AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(30, TimeUnit.SECONDS);

        runtime.submitCallable((Callable<Void>) () -> {
            try {
                IInputParser inputParser = getParser(configId);
                List<Solution> solutions = inputParser.parse(sentence);
                asyncResponse.resume(solutions);
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                asyncResponse.resume(new InternalServerErrorException());
            }

            return null;
        }, null);
    }

    private IInputParser getParser(String configId) throws Exception {
        createParserIfAbsent(configId);
        return cache.get(configId);
    }

    private void createParserIfAbsent(String configId) throws Exception {
        if (!cache.containsKey(configId)) {
            ILifecycleTask parserTask = parserProvider.get();
            ParserConfiguration parserConfiguration = fetchParserConfiguration(configId);
            parserTask.configure(parserConfiguration.getConfig());
            parserTask.setExtensions(parserConfiguration.getExtensions());
            cache.put(configId, (IInputParser) parserTask.getComponent());
        }
    }

    private ParserConfiguration fetchParserConfiguration(String configId) throws ServiceException {
        URI resourceUri = URI.create(IRestParserStore.resourceURI + configId);
        return resourceClientLibrary.getResource(resourceUri, ParserConfiguration.class);
    }
}
