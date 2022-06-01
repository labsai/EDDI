package ai.labs.eddi.modules.templating;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.models.ExtensionDescriptor;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.ImageOutputItem;
import ai.labs.eddi.modules.output.model.types.QuickReplyOutputItem;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.StringUtilities.joinStrings;

/**
 * @author ginccc
 */
@ApplicationScoped
public class OutputTemplateTask implements ILifecycleTask {
    public static final String ID = "ai.labs.templating";
    private static final String OUTPUT_HTML = "output:html";
    private static final String PRE_TEMPLATED = "preTemplated";
    private static final String POST_TEMPLATED = "postTemplated";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_QUICK_REPLIES = "quickReplies";
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final IDataFactory dataFactory;
    private final ObjectMapper objectMapper;

    private static final Logger log = Logger.getLogger(OutputTemplateTask.class);

    @Inject
    public OutputTemplateTask(ITemplatingEngine templatingEngine,
                              IMemoryItemConverter memoryItemConverter,
                              IDataFactory dataFactory,
                              ObjectMapper objectMapper) {
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
        this.dataFactory = dataFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return "ai.labs.templating";
    }

    @Override
    public String getType() {
        return KEY_OUTPUT;
    }

    @Override
    public void execute(IConversationMemory memory, Object ignored) {
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

                    if (preTemplated instanceof TextOutputItem textOutput) {
                        textOutput = new TextOutputItem(textOutput.getText(), textOutput.getDelay());
                        textOutput.setText(templatingEngine.processTemplate(textOutput.getText(), contextMap, templateMode));
                        postTemplated = textOutput;
                    } else if (preTemplated instanceof ImageOutputItem imageOutput) {
                        imageOutput = new ImageOutputItem(imageOutput.getUri(), imageOutput.getAlt());
                        imageOutput.setUri(templatingEngine.processTemplate(imageOutput.getUri(), contextMap, templateMode));
                        imageOutput.setAlt(templatingEngine.processTemplate(imageOutput.getAlt(), contextMap, templateMode));
                        postTemplated = imageOutput;
                    } else if (preTemplated instanceof QuickReplyOutputItem qrOutput) {
                        qrOutput = new QuickReplyOutputItem(qrOutput.getValue(), qrOutput.getExpressions(), qrOutput.getIsDefault());
                        qrOutput.setValue(templatingEngine.processTemplate(qrOutput.getValue(), contextMap, templateMode));
                        qrOutput.setExpressions(templatingEngine.processTemplate(qrOutput.getExpressions(), contextMap, templateMode));
                        postTemplated = qrOutput;
                    } else if (preTemplated instanceof Map) {
                        var tmpMap = new LinkedHashMap<>(convertObjectToMap(preTemplated));

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

    private Map<String, Object> convertObjectToMap(Object preTemplated) {
        return objectMapper.convertValue(preTemplated, new TypeReference<>() {});
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
                              IData<?> dataText,
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
        IData<Object> processedData = dataFactory.createData(newOutputKey, dataValue);
        memory.getCurrentStep().storeData(processedData);
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Templating");
        return extensionDescriptor;
    }
}
