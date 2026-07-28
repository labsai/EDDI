/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;
import ai.labs.eddi.configs.apicalls.model.QuickRepliesBuildingInstruction;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import ai.labs.eddi.utils.PathNavigator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class PrePostUtils {
    private static final String KEY_TYPE = "type";
    private static final String KEY_TEXT = "text";
    private static final String KEY_VALUE_ALTERNATIVES = "valueAlternatives";
    private static final String KEY_VALUE = "value";
    private static final String KEY_EXPRESSIONS = "expressions";

    /**
     * Used to assemble output items / quick replies as a JSON object tree. Building
     * them by string concatenation is unsafe: the values are rendered from upstream
     * API responses, and a double quote in such a value would escape its JSON
     * string and inject arbitrary items into the agent's reply.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;

    private static final Logger LOGGER = Logger.getLogger(PrePostUtils.class);

    @Inject
    public PrePostUtils(IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter, ITemplatingEngine templatingEngine,
            IDataFactory dataFactory) {
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
    }

    public Map<String, Object> executePreRequestPropertyInstructions(IConversationMemory memory, Map<String, Object> templateDataObjects,
                                                                     PreRequest preRequest)
            throws ITemplatingEngine.TemplateEngineException {

        if (preRequest != null && preRequest.getPropertyInstructions() != null) {
            var propertyInstructions = preRequest.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, 0, false, memory, templateDataObjects);
            templateDataObjects = memoryItemConverter.convert(memory);
        }
        return templateDataObjects;
    }

    public void executePropertyInstructions(List<PropertyInstruction> propertyInstructions, int httpCode, boolean validationError,
                                            IConversationMemory memory, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        if (propertyInstructions != null) {
            for (PropertyInstruction propertyInstruction : propertyInstructions) {
                if ((validationError && propertyInstruction.getRunOnValidationError())
                        || (httpCode == 0 || verifyHttpCode(propertyInstruction.getHttpCodeValidator(), httpCode))) {

                    String propertyName = propertyInstruction.getName();
                    checkNotNull(propertyName, "name");
                    propertyName = templateValues(propertyName, templateDataObjects);

                    String path = propertyInstruction.getFromObjectPath();
                    checkNotNull(path, "fromObjectPath");

                    Property.Scope scope = propertyInstruction.getScope();
                    Object propertyValue;
                    try {
                        if (!isNullOrEmpty(path)) {
                            propertyValue = PathNavigator.getValue(path, templateDataObjects);
                        } else {
                            propertyValue = propertyInstruction.getValueString();
                        }

                        if (!isNullOrEmpty(propertyValue) && propertyValue instanceof String propertyValueString) {
                            var value = templateValues(propertyValueString, templateDataObjects);
                            var valueTrimmed = value.trim();
                            if (propertyInstruction.getConvertToObject() && valueTrimmed.startsWith("{") && valueTrimmed.endsWith("}")) {
                                try {
                                    propertyValue = jsonSerialization.deserialize(valueTrimmed);
                                } catch (IOException e) {
                                    propertyValue = value;
                                }
                            } else {
                                propertyValue = value;
                            }
                        } else {
                            propertyValue = "";
                        }

                        if (propertyValue instanceof String s) {
                            memory.getConversationProperties().put(propertyName, new Property(propertyName, s, scope));
                        } else if (propertyValue instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            var m = (Map<String, Object>) propertyValue;
                            memory.getConversationProperties().put(propertyName, new Property(propertyName, m, scope));
                        } else if (propertyValue instanceof List<?>) {
                            @SuppressWarnings("unchecked")
                            var l = (List<Object>) propertyValue;
                            memory.getConversationProperties().put(propertyName, new Property(propertyName, l, scope));
                        } else if (propertyValue instanceof Integer i) {
                            memory.getConversationProperties().put(propertyName, new Property(propertyName, i, scope));
                        } else if (propertyValue instanceof Float f) {
                            memory.getConversationProperties().put(propertyName, new Property(propertyName, f, scope));
                        } else if (propertyValue instanceof Boolean b) {
                            memory.getConversationProperties().put(propertyName, new Property(propertyName, b, scope));
                        }

                        templateDataObjects.put("properties", memory.getConversationProperties().toMap());
                    } catch (Exception e) {
                        LOGGER.error(e.getLocalizedMessage(), e);
                    }
                }
            }
        }
    }

    public boolean verifyHttpCode(HttpCodeValidator httpCodeValidator, int httpCode) {
        if (httpCodeValidator == null) {
            httpCodeValidator = HttpCodeValidator.DEFAULT;
        } else {
            if (httpCodeValidator.getRunOnHttpCode() == null) {
                httpCodeValidator.setRunOnHttpCode(HttpCodeValidator.DEFAULT.getRunOnHttpCode());
            }
            if (httpCodeValidator.getSkipOnHttpCode() == null) {
                httpCodeValidator.setSkipOnHttpCode(HttpCodeValidator.DEFAULT.getSkipOnHttpCode());
            }
        }

        return httpCodeValidator.getRunOnHttpCode().contains(httpCode) && !httpCodeValidator.getSkipOnHttpCode().contains(httpCode);
    }

    public String templateValues(String toBeTemplated, Map<String, Object> properties) throws ITemplatingEngine.TemplateEngineException {

        return templatingEngine.processTemplate(toBeTemplated, properties);
    }

    public void createMemoryEntry(IConversationMemory.IWritableConversationStep currentStep, Object responseObject, String responseObjectName,
                                  String outputKey) {

        var memoryDataName = outputKey + ":" + responseObjectName;
        IData<Object> httpResponseData = dataFactory.createData(memoryDataName, responseObject);
        currentStep.storeData(httpResponseData);
        Map<String, Object> map = new HashMap<>();
        map.put(responseObjectName, responseObject);
        currentStep.addConversationOutputMap(outputKey, map);
    }

    public void runPostResponse(IConversationMemory memory, PostResponse postResponse, Map<String, Object> templateDataObjects, int httpCode,
                                boolean validationError)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        if (postResponse != null) {
            var propertyInstructions = postResponse.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, httpCode, validationError, memory, templateDataObjects);

            buildOutput(memory, templateDataObjects, httpCode, postResponse);
            buildQuickReplies(memory, templateDataObjects, httpCode, postResponse);
        }

    }

    private void buildOutput(IConversationMemory memory, Map<String, Object> templateDataObjects, int httpCode, PostResponse postResponse)
            throws ITemplatingEngine.TemplateEngineException {

        var outputBuildInstructions = postResponse.getOutputBuildInstructions();
        if (outputBuildInstructions != null) {
            List<Object> output = new LinkedList<>();
            for (var buildingInstruction : outputBuildInstructions) {
                if (verifyHttpCode(buildingInstruction.getHttpCodeValidator(), httpCode)) {

                    output.addAll(buildOutput(buildingInstruction.getIterationObjectName(), buildingInstruction.getPathToTargetArray(),
                            buildingInstruction.getTemplateFilterExpression(), buildingInstruction.getOutputType(),
                            buildingInstruction.getOutputValue(), templateDataObjects));
                }
            }

            var context = new Context(Context.ContextType.object, output);
            IData<Context> contextData = dataFactory.createData("context:output", context);
            memory.getCurrentStep().storeData(contextData);
        }
    }

    private void buildQuickReplies(IConversationMemory memory, Map<String, Object> templateDataObjects, int httpCode, PostResponse postResponse)
            throws ITemplatingEngine.TemplateEngineException {

        var qrBuildInstructions = postResponse.getQrBuildInstructions();
        if (qrBuildInstructions != null) {
            List<Object> quickReplies = new LinkedList<>();
            for (QuickRepliesBuildingInstruction qrBuildInstruction : qrBuildInstructions) {
                if (verifyHttpCode(qrBuildInstruction.getHttpCodeValidator(), httpCode)) {

                    quickReplies.addAll(buildQuickReplies(qrBuildInstruction.getIterationObjectName(), qrBuildInstruction.getPathToTargetArray(),
                            qrBuildInstruction.getTemplateFilterExpression(), qrBuildInstruction.getQuickReplyValue(),
                            qrBuildInstruction.getQuickReplyExpressions(), templateDataObjects));
                }
            }

            var context = new Context(Context.ContextType.object, quickReplies);
            IData<Context> contextData = dataFactory.createData("context:quickReplies", context);
            memory.getCurrentStep().storeData(contextData);
        }
    }

    private List<Object> buildOutput(String iterationObjectName, String pathToTargetArray, String templateFilterExpression, String outputType,
                                     String outputValue, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        if (!isNullOrEmpty(pathToTargetArray)) {
            List<Object> output = new LinkedList<>();
            var renderedRows = renderPerIteration(iterationObjectName, pathToTargetArray, templateFilterExpression,
                    List.of(nullToEmpty(outputValue)), templateDataObjects);
            for (var renderedRow : renderedRows) {
                output.add(createOutputItem(outputType, renderedRow.getFirst()));
            }
            return output;

        } else {
            var outputText = templatingEngine.processTemplate(outputValue, templateDataObjects);
            return List.of(new OutputValue(List.of(new TextOutputItem(outputText))));
        }
    }

    private List<Object> buildQuickReplies(String iterationObjectName, String pathToTargetArray, String templateFilterExpression,
                                           String quickReplyValue, String quickReplyExpressions, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        List<Object> quickReplies = new LinkedList<>();
        var renderedRows = renderPerIteration(iterationObjectName, pathToTargetArray, templateFilterExpression,
                List.of(nullToEmpty(quickReplyValue), nullToEmpty(quickReplyExpressions)), templateDataObjects);
        for (var renderedRow : renderedRows) {
            quickReplies.add(createQuickReply(renderedRow.get(0), renderedRow.get(1)));
        }
        return quickReplies;
    }

    /**
     * Render each element of {@code pathToTargetArray} into a plain string,
     * honouring the optional filter expression. Used to expand batch requests.
     */
    public List<Object> buildIterationValues(String iterationObjectName, String pathToTargetArray, String templateFilterExpression,
                                             Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        List<Object> values = new LinkedList<>();
        var valueTemplate = "{" + iterationObjectName + "}";
        var renderedRows = renderPerIteration(iterationObjectName, pathToTargetArray, templateFilterExpression, List.of(valueTemplate),
                templateDataObjects);
        for (var renderedRow : renderedRows) {
            values.add(renderedRow.getFirst());
        }
        return values;
    }

    /**
     * Render {@code valueTemplates} once per element of {@code pathToTargetArray}.
     * <p>
     * Iteration and the optional filter expression are still evaluated by a single
     * Qute template, so their semantics are unchanged — but the rendered values are
     * separated by delimiters carrying a per-invocation random nonce instead of
     * being rendered into a hand-concatenated JSON document. Upstream content
     * cannot forge such a delimiter, so no value can break out of its field.
     *
     * @return one list of rendered values per iterated element, each with the same
     *         size and order as {@code valueTemplates}
     */
    private List<List<String>> renderPerIteration(String iterationObjectName, String pathToTargetArray, String templateFilterExpression,
                                                  List<String> valueTemplates, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        var nonce = UUID.randomUUID().toString().replace("-", "");
        var fieldDelimiter = "eddiField" + nonce;
        var rowDelimiter = "eddiRow" + nonce;

        var template = new StringBuilder();
        template.append("{#for ").append(iterationObjectName).append(" in ").append(pathToTargetArray).append("}");

        boolean filtered = !isNullOrEmpty(templateFilterExpression);
        if (filtered) {
            template.append("{#if ").append(templateFilterExpression).append("}");
        }

        for (int i = 0; i < valueTemplates.size(); i++) {
            if (i > 0) {
                template.append(fieldDelimiter);
            }
            template.append(valueTemplates.get(i));
        }
        template.append(rowDelimiter);

        if (filtered) {
            template.append("{/if}");
        }
        template.append("{/for}");

        var rendered = templatingEngine.processTemplate(template.toString(), templateDataObjects);
        if (isNullOrEmpty(rendered)) {
            return List.of();
        }

        List<List<String>> rows = new LinkedList<>();
        var renderedRows = rendered.split(Pattern.quote(rowDelimiter), -1);
        // Every rendered row is terminated by the row delimiter, so the trailing chunk
        // is the (empty) remainder and never a row of its own.
        for (int i = 0; i < renderedRows.length - 1; i++) {
            var renderedFields = renderedRows[i].split(Pattern.quote(fieldDelimiter), -1);
            List<String> fields = new ArrayList<>(valueTemplates.size());
            for (int field = 0; field < valueTemplates.size(); field++) {
                fields.add(field < renderedFields.length ? renderedFields[field] : "");
            }
            rows.add(fields);
        }

        return rows;
    }

    private static Map<String, Object> createOutputItem(String outputType, String text) {
        ObjectNode valueAlternative = OBJECT_MAPPER.createObjectNode();
        valueAlternative.put(KEY_TYPE, outputType);
        valueAlternative.put(KEY_TEXT, text);

        ObjectNode outputItem = OBJECT_MAPPER.createObjectNode();
        outputItem.put(KEY_TYPE, outputType);
        outputItem.set(KEY_VALUE_ALTERNATIVES, OBJECT_MAPPER.createArrayNode().add(valueAlternative));

        return toMap(outputItem);
    }

    private static Map<String, Object> createQuickReply(String value, String expressions) {
        ObjectNode quickReply = OBJECT_MAPPER.createObjectNode();
        quickReply.put(KEY_VALUE, value);
        quickReply.put(KEY_EXPRESSIONS, expressions);

        return toMap(quickReply);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ObjectNode objectNode) {
        return OBJECT_MAPPER.convertValue(objectNode, Map.class);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
