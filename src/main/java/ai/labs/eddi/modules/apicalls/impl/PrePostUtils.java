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
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.SecretValueScrubber;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.properties.impl.PropertyInstructionExecutor;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class PrePostUtils {
    private static final String KEY_TYPE = "type";
    private static final String KEY_TEXT = "text";
    private static final String KEY_VALUE_ALTERNATIVES = "valueAlternatives";
    private static final String KEY_VALUE = "value";
    private static final String KEY_EXPRESSIONS = "expressions";

    /**
     * Step data key under which failed {@code preRequest} / {@code postResponse}
     * property instructions are recorded (a list of messages).
     */
    public static final String KEY_PROPERTY_INSTRUCTION_ERRORS = "propertyInstructions:errors";

    /**
     * Template data keys under which the per-invocation field/row delimiters are
     * handed to Qute. They deliberately live in the template <em>data</em> and not
     * in the template <em>text</em>: {@code ITemplatingEngine} caches compiled
     * templates keyed on the template string, so a nonce baked into the text would
     * make every single execution a cache miss (and churn the bounded cache).
     * Keeping the text stable and the values random preserves both properties — the
     * compiled template is reused, and upstream content still cannot forge a
     * delimiter it cannot guess.
     */
    private static final String KEY_FIELD_DELIMITER = "eddiFieldDelimiter";
    private static final String KEY_ROW_DELIMITER = "eddiRowDelimiter";

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
    private final PropertyInstructionExecutor propertyInstructionExecutor;

    private static final Logger LOGGER = Logger.getLogger(PrePostUtils.class);

    @Inject
    public PrePostUtils(IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter, ITemplatingEngine templatingEngine,
            IDataFactory dataFactory, SecretPropertyVault secretPropertyVault) {
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
        this.propertyInstructionExecutor = new PropertyInstructionExecutor(templatingEngine, secretPropertyVault, jsonSerialization::deserialize);
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

    /**
     * Runs the instructions whose HTTP-code gate matches, each through the shared
     * {@link PropertyInstructionExecutor} — so a {@code postResponse} instruction
     * stores exactly what a {@code property.json} instruction would: a number as a
     * number, an object as an object.
     * <p>
     * A failing instruction does not fail the call or the turn (the response has
     * already been received and the remaining instructions still apply), but it is
     * no longer only a log line: the reason is logged with the conversation id and
     * recorded in the step under {@value #KEY_PROPERTY_INSTRUCTION_ERRORS}, which a
     * detailed conversation snapshot shows. A {@code scope: "secret"} instruction
     * that cannot be vaulted is the exception — it fails, as it does in the
     * property setter, rather than leaving the credential unset or in plaintext.
     *
     * @return the plaintexts that {@code scope: "secret"} instructions vaulted —
     *         the caller removes them from anything it still holds that did not
     *         pass through conversation memory (an LLM tool result); empty when
     *         none were
     */
    public Set<String> executePropertyInstructions(List<PropertyInstruction> propertyInstructions, int httpCode, boolean validationError,
                                                   IConversationMemory memory, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        Set<String> vaulted = new LinkedHashSet<>();
        if (propertyInstructions != null) {
            for (PropertyInstruction propertyInstruction : propertyInstructions) {
                if ((validationError && Boolean.TRUE.equals(propertyInstruction.getRunOnValidationError()))
                        || (httpCode == 0 || verifyHttpCode(propertyInstruction.getHttpCodeValidator(), httpCode))) {
                    try {
                        String plaintext = propertyInstructionExecutor.apply(propertyInstruction, memory, templateDataObjects);
                        if (plaintext != null) {
                            vaulted.add(plaintext);
                        }
                    } catch (LifecycleException | RuntimeException e) {
                        if (propertyInstruction.getScope() == Property.Scope.secret) {
                            // Fail closed: a credential that could not be vaulted must not be
                            // left unset (the next call fails with a 401 that names nothing)
                            // or anywhere in plaintext.
                            throw e instanceof SecretPropertyVault.SecretPropertyException secretFailure
                                    ? secretFailure
                                    : new SecretPropertyVault.SecretPropertyException(e.getMessage(), e);
                        }
                        recordFailure(memory, propertyInstruction, e);
                    }
                }
            }
        }
        return vaulted;
    }

    private void recordFailure(IConversationMemory memory, PropertyInstruction instruction, Exception e) {
        String message = "Property instruction '" + instruction.getName() + "' failed: " + e.getLocalizedMessage();
        String conversationId = memory != null ? memory.getConversationId() : null;
        LOGGER.warnf(e, "[conversation %s] %s", conversationId, message);
        IConversationMemory.IWritableConversationStep currentStep = memory != null ? memory.getCurrentStep() : null;
        if (currentStep == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        IData<List<String>> existing = currentStep.getLatestData(KEY_PROPERTY_INSTRUCTION_ERRORS);
        if (existing != null && existing.getResult() != null) {
            errors.addAll(existing.getResult());
        }
        errors.add(message);
        currentStep.storeData(dataFactory.createData(KEY_PROPERTY_INSTRUCTION_ERRORS, errors));
    }

    /**
     * Whether an instruction with this validator runs for {@code httpCode}. The
     * validator belongs to the cached, shared configuration: the defaults for an
     * unset list are applied locally, never written back into it.
     */
    public boolean verifyHttpCode(HttpCodeValidator httpCodeValidator, int httpCode) {
        var runOn = httpCodeValidator != null && httpCodeValidator.getRunOnHttpCode() != null
                ? httpCodeValidator.getRunOnHttpCode()
                : HttpCodeValidator.DEFAULT.getRunOnHttpCode();
        var skipOn = httpCodeValidator != null && httpCodeValidator.getSkipOnHttpCode() != null
                ? httpCodeValidator.getSkipOnHttpCode()
                : HttpCodeValidator.DEFAULT.getSkipOnHttpCode();
        return runOn.contains(httpCode) && !skipOn.contains(httpCode);
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

    /**
     * @return the plaintexts the post-response {@code scope: "secret"} instructions
     *         vaulted, see {@link #executePropertyInstructions}
     */
    public Set<String> runPostResponse(IConversationMemory memory, PostResponse postResponse, Map<String, Object> templateDataObjects,
                                       int httpCode, boolean validationError)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        Set<String> vaulted = Set.of();
        if (postResponse != null) {
            var propertyInstructions = postResponse.getPropertyInstructions();
            vaulted = executePropertyInstructions(propertyInstructions, httpCode, validationError, memory, templateDataObjects);
            // The template data still holds the response the token was read from, and
            // the output and quick-reply templates below render from it: without this, a
            // postResponse output of {tokenResponse.access_token} put the plaintext the
            // instruction just vaulted into the conversation output.
            scrubTemplateData(templateDataObjects, vaulted);

            buildOutput(memory, templateDataObjects, httpCode, postResponse);
            buildQuickReplies(memory, templateDataObjects, httpCode, postResponse);
        }
        return vaulted;
    }

    /**
     * Replaces, in place, every value of {@code templateDataObjects} that carries
     * one of the {@code vaulted} plaintexts with a scrubbed copy.
     */
    private static void scrubTemplateData(Map<String, Object> templateDataObjects, Set<String> vaulted) {
        if (templateDataObjects == null || vaulted == null || vaulted.isEmpty()) {
            return;
        }
        templateDataObjects.replaceAll((key, value) -> {
            // The properties just vaulted are in here too, as references whose slot name
            // ends in the property name — a plaintext occurring in that name must not
            // break them.
            Object cleaned = SecretValueScrubber.scrubDeepKeepingReferences(value, vaulted, MemoryKeys.SECRET_INPUT_PLACEHOLDER);
            return cleaned != null ? cleaned : value;
        });
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
            context.setServerGenerated(true);
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
            // Built from this agent's own qrBuildInstructions: its expressions are
            // configuration, so the next turn's parser may honour them — unlike quick
            // replies a client sends as context (see OutputGenerationTask).
            context.setServerGenerated(true);
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
     * <p>
     * The nonce is passed as template <em>data</em> ({@link #KEY_FIELD_DELIMITER} /
     * {@link #KEY_ROW_DELIMITER}), never concatenated into the template text, so
     * the generated template string is identical for every execution of the same
     * instruction and stays reusable from the compiled-template cache.
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
                template.append('{').append(KEY_FIELD_DELIMITER).append('}');
            }
            template.append(valueTemplates.get(i));
        }
        template.append('{').append(KEY_ROW_DELIMITER).append('}');

        if (filtered) {
            template.append("{/if}");
        }
        template.append("{/for}");

        // Render against a copy so the caller's template data model is not polluted
        // with the delimiters — and so a stale delimiter can never leak into a later
        // render.
        Map<String, Object> renderData = templateDataObjects == null ? new HashMap<>() : new HashMap<>(templateDataObjects);
        renderData.put(KEY_FIELD_DELIMITER, fieldDelimiter);
        renderData.put(KEY_ROW_DELIMITER, rowDelimiter);

        var rendered = templatingEngine.processTemplate(template.toString(), renderData);
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
