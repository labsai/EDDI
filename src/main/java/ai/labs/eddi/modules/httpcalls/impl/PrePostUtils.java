package ai.labs.eddi.modules.httpcalls.impl;

import ai.labs.eddi.configs.http.model.HttpCodeValidator;
import ai.labs.eddi.configs.http.model.PostResponse;
import ai.labs.eddi.configs.http.model.PreRequest;
import ai.labs.eddi.configs.http.model.QuickRepliesBuildingInstruction;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import ognl.Ognl;
import ognl.OgnlException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class PrePostUtils {
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;

    private static final Logger LOGGER = Logger.getLogger(PrePostUtils.class);

    @Inject
    public PrePostUtils(IJsonSerialization jsonSerialization,
                        IMemoryItemConverter memoryItemConverter,
                        ITemplatingEngine templatingEngine,
                        IDataFactory dataFactory) {
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
    }

    public Map<String, Object> executePreRequestPropertyInstructions(IConversationMemory memory,
                                                                     Map<String, Object> templateDataObjects,
                                                                     PreRequest preRequest)
            throws ITemplatingEngine.TemplateEngineException {

        if (preRequest != null && preRequest.getPropertyInstructions() != null) {
            var propertyInstructions = preRequest.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, 0, false, memory, templateDataObjects);
            templateDataObjects = memoryItemConverter.convert(memory);
        }
        return templateDataObjects;
    }

    public void executePropertyInstructions(List<PropertyInstruction> propertyInstructions,
                                            int httpCode, boolean validationError, IConversationMemory memory,
                                            Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        if (propertyInstructions != null) {
            for (PropertyInstruction propertyInstruction : propertyInstructions) {
                if ((validationError && propertyInstruction.getRunOnValidationError()) || (httpCode == 0 ||
                        verifyHttpCode(propertyInstruction.getHttpCodeValidator(), httpCode))) {

                    String propertyName = propertyInstruction.getName();
                    checkNotNull(propertyName, "name");
                    propertyName = templateValues(propertyName, templateDataObjects);

                    String path = propertyInstruction.getFromObjectPath();
                    checkNotNull(path, "fromObjectPath");

                    Property.Scope scope = propertyInstruction.getScope();
                    Object propertyValue;
                    try {
                        if (!isNullOrEmpty(path)) {
                            propertyValue = Ognl.getValue(path, templateDataObjects);
                        } else {
                            propertyValue = propertyInstruction.getValueString();
                        }

                        if (!isNullOrEmpty(propertyValue) && propertyValue instanceof String propertyValueString) {
                            var value = templateValues(propertyValueString, templateDataObjects);
                            var valueTrimmed = value.trim();
                            if (propertyInstruction.getConvertToObject() &&
                                    valueTrimmed.startsWith("{") && valueTrimmed.endsWith("}")) {
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
                            memory.getConversationProperties().put(propertyName,
                                    new Property(propertyName, s, scope));
                        } else if (propertyValue instanceof Map m) {
                            memory.getConversationProperties().put(propertyName,
                                    new Property(propertyName, m, scope));
                        } else if (propertyValue instanceof List l) {
                            memory.getConversationProperties().put(propertyName,
                                    new Property(propertyName, l, scope));
                        } else if (propertyValue instanceof Integer i) {
                            memory.getConversationProperties().put(propertyName,
                                    new Property(propertyName, i, scope));
                        } else if (propertyValue instanceof Float f) {
                            memory.getConversationProperties().put(propertyName,
                                    new Property(propertyName, f, scope));
                        } else if (propertyValue instanceof Boolean b) {
                            memory.getConversationProperties().put(propertyName,
                                    new Property(propertyName, b, scope));
                        }

                        templateDataObjects.put("properties", memory.getConversationProperties().toMap());
                    } catch (OgnlException e) {
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

        return httpCodeValidator.getRunOnHttpCode().contains(httpCode) &&
                !httpCodeValidator.getSkipOnHttpCode().contains(httpCode);
    }

    public String templateValues(String toBeTemplated, Map<String, Object> properties)
            throws ITemplatingEngine.TemplateEngineException {

        return templatingEngine.processTemplate(toBeTemplated, properties);
    }

    public void createMemoryEntry(IConversationMemory.IWritableConversationStep currentStep,
                                  Object responseObject,
                                  String responseObjectName, String outputKey) {

        var memoryDataName = outputKey + ":" + responseObjectName;
        IData<Object> httpResponseData = dataFactory.createData(memoryDataName, responseObject);
        currentStep.storeData(httpResponseData);
        Map<String, Object> map = new HashMap<>();
        map.put(responseObjectName, responseObject);
        currentStep.addConversationOutputMap(outputKey, map);
    }

    public void runPostResponse(IConversationMemory memory,
                                PostResponse postResponse,
                                Map<String, Object> templateDataObjects,
                                int httpCode, boolean validationError)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        if (postResponse != null) {
            var propertyInstructions = postResponse.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, httpCode, validationError, memory, templateDataObjects);

            buildOutput(memory, templateDataObjects, httpCode, postResponse);
            buildQuickReplies(memory, templateDataObjects, httpCode, postResponse);
        }


    }

    private void buildOutput(IConversationMemory memory, Map<String, Object> templateDataObjects,
                             int httpCode, PostResponse postResponse)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        var outputBuildInstructions = postResponse.getOutputBuildInstructions();
        if (outputBuildInstructions != null) {
            List<Object> output = new LinkedList<>();
            for (var buildingInstruction : outputBuildInstructions) {
                if (verifyHttpCode(buildingInstruction.getHttpCodeValidator(), httpCode)) {

                    output.addAll(
                            buildOutput(
                                    buildingInstruction.getIterationObjectName(),
                                    buildingInstruction.getPathToTargetArray(),
                                    buildingInstruction.getTemplateFilterExpression(),
                                    buildingInstruction.getOutputType(),
                                    buildingInstruction.getOutputValue(),
                                    templateDataObjects));
                }
            }

            var context = new Context(Context.ContextType.object, output);
            IData<Context> contextData = dataFactory.createData("context:output", context);
            memory.getCurrentStep().storeData(contextData);
        }
    }

    private void buildQuickReplies(IConversationMemory memory, Map<String, Object> templateDataObjects,
                                   int httpCode, PostResponse postResponse)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        var qrBuildInstructions = postResponse.getQrBuildInstructions();
        if (qrBuildInstructions != null) {
            List<Object> quickReplies = new LinkedList<>();
            for (QuickRepliesBuildingInstruction qrBuildInstruction : qrBuildInstructions) {
                if (verifyHttpCode(qrBuildInstruction.getHttpCodeValidator(), httpCode)) {

                    quickReplies.addAll(
                            buildQuickReplies(
                                    qrBuildInstruction.getIterationObjectName(),
                                    qrBuildInstruction.getPathToTargetArray(),
                                    qrBuildInstruction.getTemplateFilterExpression(),
                                    qrBuildInstruction.getQuickReplyValue(),
                                    qrBuildInstruction.getQuickReplyExpressions(),
                                    templateDataObjects));
                }
            }

            var context = new Context(Context.ContextType.object, quickReplies);
            IData<Context> contextData = dataFactory.createData("context:quickReplies", context);
            memory.getCurrentStep().storeData(contextData);
        }
    }

    private List<Object> buildOutput(String iterationObjectName,
                                     String pathToTargetArray,
                                     String templateFilterExpression,
                                     String outputType,
                                     String outputValue,
                                     Map<String, Object> templateDataObjects)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        if (!isNullOrEmpty(pathToTargetArray)) {

            final String outputTemplate = "    {" +
                    "        \"type\":\"" + outputType + "\"," +
                    "        \"valueAlternatives\":[{" +
                    "               \"type\":\"" + outputType + "\"," +
                    "               \"text\":\"" + outputValue + "\"" +
                    "        }]" +
                    "    }";
            return buildListFromJson(iterationObjectName,
                    pathToTargetArray, templateFilterExpression, outputTemplate, templateDataObjects);

        } else {
            var outputText = templatingEngine.processTemplate(outputValue, templateDataObjects);
            return List.of(new OutputValue(List.of(new TextOutputItem(outputText))));
        }
    }

    private List<Object> buildQuickReplies(String iterationObjectName,
                                           String pathToTargetArray,
                                           String templateFilterExpression,
                                           String quickReplyValue,
                                           String quickReplyExpressions,
                                           Map<String, Object> templateDataObjects)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        final String quickReplyTemplate = "    {" +
                "        \"value\":\"" + quickReplyValue + "\"," +
                "        \"expressions\":\"" + quickReplyExpressions + "\"" +
                "    },";

        return buildListFromJson(iterationObjectName,
                pathToTargetArray, templateFilterExpression, quickReplyTemplate, templateDataObjects);
    }

    public List<Object> buildListFromJson(String iterationObjectName,
                                          String pathToTargetArray,
                                          String templateFilterExpression,
                                          String iterationValue,
                                          Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, IOException {

        String templateCode = "[" +
                "[# th:each=\"" + iterationObjectName + " : ${" + pathToTargetArray + "}\"";

        if (!isNullOrEmpty(templateFilterExpression)) {
            templateCode += "   th:object=\"${" + iterationObjectName + "}\"";
            templateCode += "   th:if=\"" + templateFilterExpression + "\"";
        }

        templateCode += "]" +
                (isNullOrEmpty(iterationValue) ? "\"[[${" + iterationObjectName + "}]]\"," : iterationValue) +
                "[/]" +
                "]";

        String jsonList = templatingEngine.processTemplate(templateCode, templateDataObjects);

        jsonList = jsonList.replace("\n", "\\\\n");

        //remove last comma of iterated array
        if (jsonList.contains(",")) {
            jsonList = new StringBuilder(jsonList).
                    deleteCharAt(jsonList.lastIndexOf(",")).toString();
        }

        return jsonSerialization.deserialize(jsonList, List.class);
    }
}
