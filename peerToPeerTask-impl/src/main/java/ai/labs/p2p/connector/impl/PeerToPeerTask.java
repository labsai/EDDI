package ai.labs.p2p.connector.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IMemoryItemConverter;
import ai.labs.resources.rest.config.p2p.model.P2PConfiguration;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.templateengine.ITemplatingEngine;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

/**
 * @author rpi
 */
@Slf4j
public class PeerToPeerTask implements ILifecycleTask {
    private static final String ID = "ai.labs.p2p";
    private static final String ACTION_KEY = "actions";
    private final IResourceClientLibrary resourceClientLibrary;
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;

    @Inject
    public PeerToPeerTask(IResourceClientLibrary resourceClientLibrary,
                             ITemplatingEngine templatingEngine,
                          IMemoryItemConverter memoryItemConverter) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
    }


    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) {
        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();

        for (String action : actions) {
            // todo: implement ask other bot
        }
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                P2PConfiguration p2PConfiguration = resourceClientLibrary.getResource(uri, P2PConfiguration.class);



            } catch (ServiceException  e) {
                log.error(e.getLocalizedMessage(), e);
                throw new PackageConfigurationException(e.getMessage(), e);
            }
        }
    }


    private String template(String toBeTemplated, Map<String, Object> templatingDataObjects)
            throws ITemplatingEngine.TemplateEngineException {
        return templatingEngine.processTemplate(toBeTemplated, templatingDataObjects);
    }


}
