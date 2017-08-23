package ai.labs.templateengine;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.model.Context;
import ai.labs.lifecycle.model.Context.ContextType;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.output.model.QuickReply;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.utilities.StringUtilities.joinStrings;

/**
 * @author ginccc
 */
@Slf4j
public class OutputTemplateTask implements ILifecycleTask {
    private static final String OUTPUT_TEXT = "output:text";
    private static final String PRE_TEMPLATED = "preTemplated";
    private static final String POST_TEMPLATED = "postTemplated";
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;

    @Inject
    public OutputTemplateTask(ITemplatingEngine templatingEngine, IDataFactory dataFactory) {
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
    }

    @Override
    public String getId() {
        return "ai.labs.templating";
    }

    @Override
    public Object getComponent() {
        return templatingEngine;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        List<IData<String>> outputDataList = currentStep.getAllData("output");
        List<IData<List<QuickReply>>> quickReplyDataList = currentStep.getAllData("quickReplies");
        List<IData<Context>> contextDataList = currentStep.getAllData("context");

        Map<String, Object> contextMap = prepareContext(contextDataList);

        templateOutputTexts(memory, outputDataList, contextMap);

        templatingQuickReplies(memory, quickReplyDataList, contextMap);
    }

    private HashMap<String, Object> prepareContext(List<IData<Context>> contextDataList) {
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        contextDataList.forEach(contextData -> {
            Context context = contextData.getResult();
            ContextType contextType = context.getType();
            if (contextType.equals(ContextType.object) || contextType.equals(ContextType.string)) {
                String dataKey = contextData.getKey();
                dynamicAttributesMap.put(dataKey.substring(dataKey.indexOf(":") + 1), context.getValue());
            }
        });
        return dynamicAttributesMap;
    }

    private void templateOutputTexts(IConversationMemory memory,
                                     List<IData<String>> outputDataList,
                                     Map<String, Object> ContextMap) {
        outputDataList.forEach(output -> {
            String outputKey = output.getKey();
            if (outputKey.startsWith(OUTPUT_TEXT)) {
                String preTemplated = output.getResult();

                try {
                    String postTemplated = templatingEngine.processTemplate(preTemplated, ContextMap);
                    output.setResult(postTemplated);
                    templateData(memory, output, outputKey, preTemplated, postTemplated);
                } catch (ITemplatingEngine.TemplateEngineException e) {
                    log.error(e.getLocalizedMessage(), e);
                }
            }
        });
    }

    private void templatingQuickReplies(IConversationMemory memory,
                                        List<IData<List<QuickReply>>> quickReplyDataList,
                                        Map<String, Object> contextMap) {
        quickReplyDataList.forEach(quickReplyData -> {
            List<QuickReply> quickReplies = quickReplyData.getResult();
            List<QuickReply> preTemplatedQuickReplies = copyQuickReplies(quickReplies);

            quickReplies.forEach(quickReply -> {
                String preTemplatedValue = quickReply.getValue();
                try {
                    String postTemplatedValue = templatingEngine.processTemplate(preTemplatedValue, contextMap);
                    quickReply.setValue(postTemplatedValue);
                } catch (ITemplatingEngine.TemplateEngineException e) {
                    log.error(e.getLocalizedMessage(), e);
                }
            });

            templateData(memory, quickReplyData, quickReplyData.getKey(), preTemplatedQuickReplies, quickReplies);
        });
    }

    private List<QuickReply> copyQuickReplies(List<QuickReply> source) {
        return source.stream().map(quickReply ->
                new QuickReply(quickReply.getValue(), quickReply.getExpressions())).
                collect(Collectors.toCollection(LinkedList::new));
    }

    private void templateData(IConversationMemory memory,
                              IData dataText,
                              String dataKey,
                              Object preTemplated,
                              Object postTemplated) {

        storeTemplatedData(memory, dataKey, PRE_TEMPLATED, preTemplated);
        storeTemplatedData(memory, dataKey, POST_TEMPLATED, postTemplated);
        memory.getCurrentStep().storeData(dataText);
    }

    private void storeTemplatedData(IConversationMemory memory,
                                    String originalKey,
                                    String templateAppendix,
                                    Object dataValue) {

        String newOutputKey = joinStrings(":", originalKey, templateAppendix);
        IData processedData = dataFactory.createData(newOutputKey, dataValue);
        memory.getCurrentStep().storeData(processedData);
    }
}
