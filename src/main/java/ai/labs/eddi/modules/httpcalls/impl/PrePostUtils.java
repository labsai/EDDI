package ai.labs.eddi.modules.httpcalls.impl;

import ai.labs.eddi.configs.http.model.HttpCodeValidator;
import ai.labs.eddi.configs.http.model.PreRequest;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import ognl.Ognl;
import ognl.OgnlException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class PrePostUtils {
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;

    private static final Logger LOGGER = Logger.getLogger(PrePostUtils.class);

    @Inject
    public PrePostUtils(IJsonSerialization jsonSerialization,
                        IMemoryItemConverter memoryItemConverter,
                        ITemplatingEngine templatingEngine) {
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
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
                            var value = propertyInstruction.getValueString();

                            if (!isNullOrEmpty(value)) {
                                value = templateValues(value, templateDataObjects);
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
}
