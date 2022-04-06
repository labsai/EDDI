package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.IRestSemanticParser;
import ai.labs.eddi.modules.nlp.InputParserTask;
import ai.labs.eddi.modules.nlp.Solution;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
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

import static ai.labs.eddi.modules.nlp.DictionaryUtilities.extractExpressions;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestSemanticParser implements IRestSemanticParser {
    private final IRuntime runtime;
    private final IResourceClientLibrary resourceClientLibrary;
    private final Provider<ILifecycleTask> parserProvider;
    private final ICache<URI, InputParserTask> cache;

    private final Logger log = Logger.getLogger(RestSemanticParser.class);

    @Inject
    public RestSemanticParser(IRuntime runtime,
                              IResourceClientLibrary resourceClientLibrary,
                              ICacheFactory cacheFactory,
                              @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTasks) {
        this.runtime = runtime;
        this.resourceClientLibrary = resourceClientLibrary;
        cache = cacheFactory.getCache("parser");
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
