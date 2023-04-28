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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
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

        templateOutputTexts(currentStep, outputDataList, contextMap);
        templatingQuickReplies(currentStep, quickReplyDataList, contextMap);
    }

    private void templateOutputTexts(IWritableConversationStep currentStep,
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
                    final var preTemplated = output.getResult();
                    Object postTemplated = null;

                    if (preTemplated instanceof TextOutputItem textOutput) {
                        var textProperty = textOutput.getText();
                        textOutput = new TextOutputItem(textProperty, textOutput.getDelay());
                        if (!isNullOrEmpty(textProperty)) {
                            textOutput.setText(templatingEngine.processTemplate(textProperty, contextMap, templateMode));
                        }
                        postTemplated = textOutput;
                    } else if (preTemplated instanceof ImageOutputItem imageOutput) {
                        var uriProperty = imageOutput.getUri();
                        var altProperty = imageOutput.getAlt();
                        imageOutput = new ImageOutputItem(uriProperty, altProperty);
                        if (!isNullOrEmpty(uriProperty)) {
                            imageOutput.setUri(templatingEngine.processTemplate(uriProperty, contextMap, templateMode));
                        }
                        if (!isNullOrEmpty(altProperty)) {
                            imageOutput.setAlt(templatingEngine.processTemplate(altProperty, contextMap, templateMode));
                        }
                        postTemplated = imageOutput;
                    } else if (preTemplated instanceof QuickReplyOutputItem qrOutput) {
                        var valueProperty = qrOutput.getValue();
                        var expressionsProperty = qrOutput.getExpressions();
                        qrOutput = new QuickReplyOutputItem(valueProperty, expressionsProperty, qrOutput.getIsDefault());
                        if (!isNullOrEmpty(valueProperty)) {
                            qrOutput.setValue(templatingEngine.processTemplate(valueProperty, contextMap, templateMode));
                        }
                        if (!isNullOrEmpty(expressionsProperty)) {
                            qrOutput.setExpressions(templatingEngine.processTemplate(expressionsProperty, contextMap, templateMode));
                        }
                        postTemplated = qrOutput;
                    } else if (preTemplated instanceof Map) {
                        var tmpMap = new LinkedHashMap<>(convertObjectToMap(preTemplated));

                        for (String key : tmpMap.keySet()) {
                            Object valueObj = tmpMap.get(key);
                            if (valueObj instanceof String) {
                                String post = null;
                                var valueAsString = valueObj.toString();
                                if (!isNullOrEmpty(valueAsString)) {
                                    post = templatingEngine.processTemplate(valueAsString, contextMap, templateMode);
                                }
                                tmpMap.put(key, post);
                            }
                        }

                        postTemplated = tmpMap;
                    }

                    if (postTemplated != null) {
                        output.setResult(postTemplated);
                        templateData(currentStep, output, outputKey, preTemplated, postTemplated);
                        currentStep.replaceConversationOutputObject(KEY_OUTPUT, preTemplated, postTemplated);
                    }
                } catch (ITemplatingEngine.TemplateEngineException e) {
                    log.error(e.getLocalizedMessage(), e);
                }
            }
        });
    }

    private Map<String, Object> convertObjectToMap(Object preTemplated) {
        return objectMapper.convertValue(preTemplated, new TypeReference<>() {});
    }

    private void templatingQuickReplies(IWritableConversationStep currentStep,
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

            templateData(currentStep, quickReplyData, quickReplyData.getKey(), preTemplating, postTemplating);
            quickReplyData.setResult(postTemplating);
        });
    }

    private List<QuickReply> copyQuickReplies(List<QuickReply> source) {
        return source.stream().map(quickReply ->
                        new QuickReply(quickReply.getValue(), quickReply.getExpressions(), quickReply.getIsDefault())).
                collect(Collectors.toCollection(LinkedList::new));
    }

    private void templateData(IWritableConversationStep currentStep,
                              IData<?> dataText,
                              String dataKey,
                              Object preTemplated,
                              Object postTemplated) {

        storeTemplatedData(currentStep, dataKey, PRE_TEMPLATED, preTemplated);
        storeTemplatedData(currentStep, dataKey, POST_TEMPLATED, postTemplated);
        currentStep.storeData(dataText);
    }

    private void storeTemplatedData(IWritableConversationStep currentStep,
                                    String originalKey,
                                    String templateAppendix,
                                    Object dataValue) {

        String newOutputKey = joinStrings(":", originalKey, templateAppendix);
        IData<Object> processedData = dataFactory.createData(newOutputKey, dataValue);
        currentStep.storeData(processedData);
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Templating");
        return extensionDescriptor;
    }
}
