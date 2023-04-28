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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
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
    private final Map<URI, IInputParser> cache;

    private final Logger log = Logger.getLogger(RestSemanticParser.class);

    @Inject
    public RestSemanticParser(IRuntime runtime,
                              IResourceClientLibrary resourceClientLibrary,
                              @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTasks) {
        this.runtime = runtime;
        this.resourceClientLibrary = resourceClientLibrary;
        this.parserProvider = lifecycleTasks.get("ai.labs.parser");

        cache = new HashMap<>();
    }

    @Override
    public void parse(String configId, Integer version, String sentence, AsyncResponse asyncResponse) {
        asyncResponse.setTimeout(30, TimeUnit.SECONDS);

        runtime.submitCallable((Callable<Void>) () -> {
            try {
                URI resourceUri = URI.create(IRestParserStore.resourceURI + configId +
                        IRestParserStore.versionQueryParam + version);
                IInputParser inputParser = getParser(resourceUri);
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

    private IInputParser getParser(URI resourceUri) throws Exception {
        return createParserIfAbsent(resourceUri);
    }

    private IInputParser createParserIfAbsent(URI resourceUri) throws Exception {
        if (!cache.containsKey(resourceUri)) {
            ILifecycleTask parserTask = parserProvider.get();
            var parserConfiguration = fetchParserConfiguration(resourceUri);
            var config = parserConfiguration.getConfig();
            var extensions = parserConfiguration.getExtensions();
            var inputParser =
                    (IInputParser) parserTask.configure(
                            config != null ? config : new HashMap<>(),
                            extensions != null ? extensions : new HashMap<>());

            cache.put(resourceUri, inputParser);
            return inputParser;
        } else {
            return cache.get(resourceUri);
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
