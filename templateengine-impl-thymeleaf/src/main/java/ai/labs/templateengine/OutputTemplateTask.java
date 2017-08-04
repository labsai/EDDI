package ai.labs.templateengine;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.model.Context;
import ai.labs.lifecycle.model.Context.ContextType;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;

/**
 * @author ginccc
 */
public class OutputTemplateTask implements ILifecycleTask {
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;

    @Inject
    public OutputTemplateTask(ITemplatingEngine templatingEngine,
                              IDataFactory dataFactory) {
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
    }

    @Override
    public String getId() {
        return templatingEngine.getClass().getSimpleName();
    }

    @Override
    public Object getComponent() {
        return templatingEngine;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        List<IData<String>> outputs = memory.getCurrentStep().getAllData("output");
        List<IData<Context>> contexts = memory.getCurrentStep().getAllData("context");

        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        contexts.forEach(contextData -> {
            Context context = contextData.getResult();
            ContextType contextType = context.getType();
            if (contextType.equals(ContextType.object) || contextType.equals(ContextType.string)) {
                String dataKey = contextData.getKey();
                dynamicAttributesMap.put(dataKey.substring(dataKey.indexOf(":") + 1), context.getValue());
            }
        });

        outputs.forEach(output -> {
            String outputKey = output.getKey();
            String outputTemplate = output.getResult();
            String processedTemplate = templatingEngine.processTemplate(outputTemplate, dynamicAttributesMap);
            String processedOutputKey = "output:templated:" + outputKey.substring(outputKey.indexOf(":") + 1);
            IData<String> processedData = dataFactory.createData(processedOutputKey, processedTemplate);
            memory.getCurrentStep().storeData(processedData);
        });
    }
}
