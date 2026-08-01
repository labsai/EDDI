/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static ai.labs.eddi.utils.StringUtilities.joinStrings;

/**
 * @author ginccc
 */
@ApplicationScoped
public class OutputTemplateTask implements ILifecycleTask {
    public static final String ID = "ai.labs.templating";
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final String OUTPUT_HTML = "output:html";
    private static final String PRE_TEMPLATED = "preTemplated";
    private static final String POST_TEMPLATED = "postTemplated";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_QUICK_REPLIES = "quickReplies";

    /**
     * The single, profile-independent failure behaviour for a template that cannot
     * be rendered: the affected value collapses to an empty string. That is exactly
     * what a missing variable renders to under Qute's non-strict rendering (see
     * {@code quarkus.qute.strict-rendering}, off in every profile), so dev and prod
     * fail identically. Crucially it never ships raw "{properties.x}" template
     * syntax to the end user and never puts a {@code null} into an output or
     * quick-reply list.
     */
    private static final String FAILED_TEMPLATE_SUBSTITUTE = "";
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final IDataFactory dataFactory;
    private final ObjectMapper objectMapper;

    private static final Logger log = Logger.getLogger(OutputTemplateTask.class);

    @Inject
    public OutputTemplateTask(ITemplatingEngine templatingEngine, IMemoryItemConverter memoryItemConverter, IDataFactory dataFactory,
            ObjectMapper objectMapper) {
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
        this.dataFactory = dataFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
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

    private void templateOutputTexts(IWritableConversationStep currentStep, List<IData<Object>> outputDataList, Map<String, Object> contextMap) {
        outputDataList.forEach(output -> {
            String outputKey = output.getKey();
            TemplateMode templateMode = resolveTemplateMode(outputKey);
            if (templateMode == null) {
                return;
            }

            var templating = templatingFunction(contextMap, templateMode, outputKey);
            final var preTemplated = output.getResult();
            Object postTemplated = null;

            if (preTemplated instanceof OutputItem outputItem) {
                // Every OutputItem subtype has to implement templatedCopy(), so no
                // output type can silently skip templating (OutputItem#applyTemplating).
                postTemplated = outputItem.applyTemplating(templating);
            } else if (preTemplated instanceof Map) {
                var tmpMap = new LinkedHashMap<String, Object>(convertObjectToMap(preTemplated));
                tmpMap.replaceAll((key, valueObj) -> valueObj instanceof String valueAsString ? templating.apply(valueAsString) : valueObj);
                postTemplated = tmpMap;
            }

            if (postTemplated != null) {
                output.setResult(postTemplated);
                templateData(currentStep, output, outputKey, preTemplated, postTemplated);
                currentStep.replaceConversationOutputObject(KEY_OUTPUT, preTemplated, postTemplated);
            }
        });
    }

    /**
     * The HTML check has to come first: "output:html:..." also starts with
     * "output", so testing the generic prefix first made every output TEXT mode and
     * left the HTML branch unreachable.
     * <p>
     * Note on reachability: output keys are built by {@code OutputGenerationTask}
     * as {@code output:<outputItemType>:<action>}, and none of the currently
     * registered {@link OutputItem} subtypes ({@code text}, {@code image},
     * {@code agentFace}, {@code quickReply}, {@code inputField},
     * {@code applicationLink}, {@code button}, {@code other}) is named
     * {@code html}. The HTML branch is therefore forward-looking infrastructure for
     * a future markup output type — it is deliberately kept correct and covered so
     * that adding such a type does not silently ship unescaped conversation data
     * into markup.
     */
    private TemplateMode resolveTemplateMode(String outputKey) {
        if (outputKey.startsWith(OUTPUT_HTML)) {
            return TemplateMode.HTML;
        }
        if (outputKey.startsWith(KEY_OUTPUT)) {
            return TemplateMode.TEXT;
        }
        return null;
    }

    /**
     * Wraps the templating engine into the one failure behaviour shared by every
     * output type and by quick replies: log the failure with context, then
     * substitute an empty string. Never throws, never returns {@code null} for a
     * non-null input.
     */
    private UnaryOperator<String> templatingFunction(Map<String, Object> contextMap, TemplateMode templateMode, String dataKey) {
        return value -> {
            if (isNullOrEmpty(value)) {
                return value;
            }
            try {
                return templatingEngine.processTemplate(value, contextMap, templateMode);
            } catch (ITemplatingEngine.TemplateEngineException e) {
                log.errorf(e, "Template processing failed for '%s' (mode %s), substituting an empty string. Template was: %s", dataKey,
                        templateMode, value);
                return FAILED_TEMPLATE_SUBSTITUTE;
            }
        };
    }

    private Map<String, Object> convertObjectToMap(Object preTemplated) {
        return objectMapper.convertValue(preTemplated, new TypeReference<>() {
        });
    }

    private void templatingQuickReplies(IWritableConversationStep currentStep, List<IData<List<QuickReply>>> quickReplyDataList,
                                        Map<String, Object> contextMap) {
        quickReplyDataList.forEach(quickReplyData -> {
            var preTemplating = quickReplyData.getResult();
            var templating = templatingFunction(contextMap, TemplateMode.TEXT, quickReplyData.getKey());
            var postTemplating = copyQuickReplies(preTemplating).stream().map(quickReply -> {
                // Same failure behaviour as the output path — a failed template can no
                // longer turn a quick reply into a null entry in the list.
                quickReply.setValue(templating.apply(quickReply.getValue()));
                quickReply.setExpressions(templating.apply(quickReply.getExpressions()));
                return quickReply;
            }).collect(Collectors.toList());

            templateData(currentStep, quickReplyData, quickReplyData.getKey(), preTemplating, postTemplating);
            quickReplyData.setResult(postTemplating);
        });
    }

    private List<QuickReply> copyQuickReplies(List<QuickReply> source) {
        return source.stream().map(quickReply -> new QuickReply(quickReply.getValue(), quickReply.getExpressions(), quickReply.getIsDefault()))
                .collect(Collectors.toCollection(LinkedList::new));
    }

    private void templateData(IWritableConversationStep currentStep, IData<?> dataText, String dataKey, Object preTemplated, Object postTemplated) {

        storeTemplatedData(currentStep, dataKey, PRE_TEMPLATED, preTemplated);
        storeTemplatedData(currentStep, dataKey, POST_TEMPLATED, postTemplated);
        currentStep.storeData(dataText);
    }

    private void storeTemplatedData(IWritableConversationStep currentStep, String originalKey, String templateAppendix, Object dataValue) {

        String newOutputKey = joinStrings(":", originalKey, templateAppendix);
        IData<Object> processedData = dataFactory.createData(newOutputKey, dataValue);
        currentStep.storeData(processedData);
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(TASK_ID);
        extensionDescriptor.setDisplayName("Templating");
        return extensionDescriptor;
    }
}
