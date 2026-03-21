package ai.labs.eddi.configs.dictionary.expression;

import ai.labs.eddi.configs.pipelines.IPipelineStore;
import ai.labs.eddi.configs.pipelines.model.PipelineConfiguration;
import ai.labs.eddi.configs.dictionary.IRegularDictionaryStore;
import ai.labs.eddi.configs.dictionary.IRestExpression;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.CollectionUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestExpression implements IRestExpression {
    private final IPipelineStore PipelineStore;
    private final IRegularDictionaryStore regularDictionaryStore;



    @Inject
    public RestExpression(IPipelineStore PipelineStore,
                          IRegularDictionaryStore regularDictionaryStore) {
        this.PipelineStore = PipelineStore;
        this.regularDictionaryStore = regularDictionaryStore;
    }

    @Override
    public List<String> readExpressions(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retExpressions = new LinkedList<>();
        try {
            PipelineConfiguration PipelineConfiguration = PipelineStore.read(packageId, packageVersion);
            List<IResourceStore.IResourceId> resourceIds = readDictionaryResourceIds(PipelineConfiguration);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                List<String> expressions = regularDictionaryStore.readExpressions(resourceId.getId(), resourceId.getVersion(), filter, "asc", 0, limit);
                CollectionUtilities.addAllWithoutDuplicates(retExpressions, expressions);
            }
            return retExpressions;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    private List<IResourceStore.IResourceId> readDictionaryResourceIds(PipelineConfiguration PipelineConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (PipelineConfiguration.PipelineStep PipelineStep : PipelineConfiguration.getPipelineSteps()) {
            if (!PipelineStep.getType().toString().startsWith("eddi://ai.labs.parser")) {
                continue;
            }

            Map<String, Object> extensionTypes = PipelineStep.getExtensions();
            for (String extensionKey : extensionTypes.keySet()) {
                if (!extensionKey.equals("dictionaries")) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                var extensions = (List<Map<String, Object>>) extensionTypes.get(extensionKey);
                for (Map<String, Object> extension : extensions) {
                    if (!extension.containsKey("type") || !extension.get("type").toString().startsWith("eddi://ai.labs.parser.dictionaries.regular")) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    var config = (Map<String, Object>) extension.get("config");
                    String uri = (String) config.get("uri");
                    resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
                }

            }
        }

        return resourceIds;
    }
}
