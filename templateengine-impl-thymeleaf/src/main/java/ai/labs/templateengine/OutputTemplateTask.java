package ai.labs.templateengine;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.memory.IMemoryItemConverter;
import ai.labs.output.model.QuickReply;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.templateengine.ITemplatingEngine.TemplateMode;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.utilities.StringUtilities.joinStrings;

/**
 * @author ginccc
 */
@Slf4j
public class OutputTemplateTask implements ILifecycleTask {
    private static final String ID = "ai.labs.templating";
    private static final String OUTPUT_HTML = "output:html";
    private static final String PRE_TEMPLATED = "preTemplated";
    private static final String POST_TEMPLATED = "postTemplated";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_QUICK_REPLIES = "quickReplies";
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final IDataFactory dataFactory;

    @Inject
    public OutputTemplateTask(ITemplatingEngine templatingEngine,
                              IMemoryItemConverter memoryItemConverter,
                              IDataFactory dataFactory) {
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
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
    public void executeTask(IConversationMemory memory) {
        IWritableConversationStep currentStep = memory.getCurrentStep();
        List<IData<Object>> outputDataList = currentStep.getAllData(KEY_OUTPUT);
        List<IData<List<QuickReply>>> quickReplyDataList = currentStep.getAllData(KEY_QUICK_REPLIES);

        final Map<String, Object> contextMap = memoryItemConverter.convert(memory);

        if (!outputDataList.isEmpty()) {
            currentStep.resetConversationOutput(KEY_OUTPUT);
        }

        templateOutputTexts(memory, outputDataList, contextMap);

        if (!quickReplyDataList.isEmpty()) {
            currentStep.resetConversationOutput(KEY_QUICK_REPLIES);
        }
        templatingQuickReplies(memory, quickReplyDataList, contextMap);
    }

    private void templateOutputTexts(IConversationMemory memory,
                                     List<IData<Object>> outputDataList,
                                     Map<String, Object> contextMap) {
        outputDataList.forEach(output -> {
            String outputKey = output.getKey();
            TemplateMode templateMode = outputKey.startsWith(KEY_OUTPUT) ? TemplateMode.TEXT : null;
            if (templateMode == null) {
                templateMode = outputKey.startsWith(OUTPUT_HTML) ? TemplateMode.HTML : null;
            }

            if (templateMode != null) {
                try {
                    Object preTemplated = output.getResult();
                    Object postTemplated = null;

                    if (preTemplated instanceof String) { // keep supporting string for backwards compatibility

                        postTemplated = templatingEngine.processTemplate(preTemplated.toString(), contextMap, templateMode);

                    } else if (preTemplated instanceof Map) {
                        var tmpMap = new LinkedHashMap<>((Map<String, Object>) preTemplated);

                        for (String key : tmpMap.keySet()) {
                            Object valueObj = tmpMap.get(key);
                            if (valueObj instanceof String) {
                                String post = templatingEngine.processTemplate(valueObj.toString(), contextMap, templateMode);
                                tmpMap.put(key, post);
                            }
                        }

                        postTemplated = tmpMap;
                    }

                    output.setResult(postTemplated);
                    templateData(memory, output, outputKey, preTemplated, postTemplated);

                    IWritableConversationStep currentStep = memory.getCurrentStep();
                    currentStep.addConversationOutputList(KEY_OUTPUT, Collections.singletonList(output.getResult()));

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
            var preTemplating = quickReplyData.getResult();
            var postTemplating = copyQuickReplies(preTemplating).stream().map(quickReply -> {
                try {
                    String preTemplatedValue = quickReply.getValue();
                    String postTemplatedValue = templatingEngine.processTemplate(preTemplatedValue, contextMap);
                    quickReply.setValue(postTemplatedValue);

                    String preTemplatedExpressions = quickReply.getExpressions();
                    String postTemplatedExpressions = templatingEngine.processTemplate(preTemplatedExpressions, contextMap);
                    quickReply.setExpressions(postTemplatedExpressions);
                    return quickReply;
                } catch (ITemplatingEngine.TemplateEngineException e) {
                    log.error(e.getLocalizedMessage(), e);
                    return null;
                }
            }).collect(Collectors.toList());

            templateData(memory, quickReplyData, quickReplyData.getKey(), preTemplating, postTemplating);
            memory.getCurrentStep().addConversationOutputList(KEY_QUICK_REPLIES, postTemplating);
        });
    }

    private List<QuickReply> copyQuickReplies(List<QuickReply> source) {
        return source.stream().map(quickReply ->
                new QuickReply(quickReply.getValue(), quickReply.getExpressions(), quickReply.isDefault())).
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

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Templating");
        return extensionDescriptor;
    }
}
