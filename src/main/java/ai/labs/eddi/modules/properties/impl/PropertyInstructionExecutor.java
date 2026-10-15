/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.properties.model.PropertyValues;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.utils.PathNavigator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Runs one {@link PropertyInstruction} against a conversation — the single
 * implementation behind {@code property.json} ({@link PropertySetterTask}) and
 * the {@code preRequest} / {@code postResponse} property instructions of
 * httpcalls, MCP calls and LLM tasks ({@code PrePostUtils}).
 * <p>
 * The two used to be separate copies that had drifted apart: the
 * {@code postResponse} copy stored {@code ""} for every number, boolean, object
 * or list it read through {@code fromObjectPath} (a GitHub repository's
 * {@code stargazers_count} and {@code private} came back empty), ignored
 * {@code override}, {@code toObjectPath} and the typed value fields, and
 * overwrote a property with {@code ""} when the path resolved to nothing; the
 * property-setter copy dropped a {@code Double} or {@code Long} silently and
 * did not know {@code convertToObject}. Both now behave as documented in
 * {@code docs/properties.md}:
 * <ol>
 * <li>The {@code name} is a template, rendered against the template data.</li>
 * <li>An existing property is left alone when {@code override} is
 * {@code false}.</li>
 * <li>With a {@code fromObjectPath}, the value found there is used exactly as
 * found — typed, and never rendered again (it is conversation data: user input
 * or an upstream response). With a {@code toObjectPath} as well, the value is
 * copied there in the template data and no property is written. A path that
 * resolves to nothing writes nothing.</li>
 * <li>Without one, {@code valueString} is rendered as a template; the typed
 * fields ({@code valueObject}, {@code valueList}, {@code valueInt},
 * {@code valueLong}, {@code valueFloat}, {@code valueDouble},
 * {@code valueBoolean}) are used as given.</li>
 * <li>{@code convertToObject} turns a string shaped like a JSON object into a
 * map.</li>
 * <li>{@code scope: "secret"} vaults the value, and only a string can be
 * vaulted: anything else fails with
 * {@link SecretPropertyVault.SecretPropertyException} instead of being stored
 * in plaintext.</li>
 * </ol>
 * Stateless: one instance is shared by every conversation.
 */
public class PropertyInstructionExecutor {

    private static final Logger LOGGER = Logger.getLogger(PropertyInstructionExecutor.class);
    private static final String PROPERTIES_IDENTIFIER = "properties";

    /** Parses a JSON object for {@code convertToObject}. */
    @FunctionalInterface
    public interface JsonObjectParser {
        Object parse(String json) throws IOException;
    }

    private final ITemplatingEngine templatingEngine;
    private final SecretPropertyVault secretPropertyVault;
    private final JsonObjectParser jsonObjectParser;

    public PropertyInstructionExecutor(ITemplatingEngine templatingEngine, SecretPropertyVault secretPropertyVault,
            JsonObjectParser jsonObjectParser) {
        this.templatingEngine = templatingEngine;
        this.secretPropertyVault = secretPropertyVault;
        this.jsonObjectParser = jsonObjectParser;
    }

    /**
     * Applies {@code instruction}: writes the property into the conversation
     * properties and refreshes {@code templateDataObjects.properties}, so the next
     * instruction of the same batch sees it.
     *
     * @return the plaintext a {@code scope: "secret"} instruction vaulted, so the
     *         caller can scrub it from anything it still holds; {@code null}
     *         otherwise
     * @throws LifecycleException
     *             when the name or the value template cannot be rendered, or a
     *             {@code scope: "secret"} value cannot be vaulted (it is not a
     *             string, or the vault refused it) — never stored in plaintext
     *             instead
     */
    public String apply(PropertyInstruction instruction, IConversationMemory memory, Map<String, Object> templateDataObjects)
            throws LifecycleException {

        String name = instruction.getName();
        if (name == null) {
            throw new LifecycleException("A property instruction has no 'name'.");
        }
        name = render(name, templateDataObjects, "name of property '" + name + "'");

        var conversationProperties = memory.getConversationProperties();
        if (conversationProperties.containsKey(name) && Boolean.FALSE.equals(instruction.getOverride())) {
            return null;
        }

        Scope scope = instruction.getScope();
        String fromObjectPath = instruction.getFromObjectPath();
        Object value;
        if (!isNullOrEmpty(fromObjectPath)) {
            value = PathNavigator.getValue(fromObjectPath, templateDataObjects);
            String toObjectPath = instruction.getToObjectPath();
            if (!isNullOrEmpty(toObjectPath)) {
                PathNavigator.setValue(toObjectPath, templateDataObjects, value);
                return null;
            }
            if (value == null) {
                LOGGER.debugf("Property '%s' not set: fromObjectPath '%s' resolved to nothing (conversation %s).", name, fromObjectPath,
                        memory.getConversationId());
                return null;
            }
        } else {
            if (scope == Scope.secret && PropertyValues.hasTypedValue(instruction)) {
                // Only a string can be vaulted. The typed value fields used to be stored
                // under scope:secret as plaintext properties; they are refused here and
                // rejected when the configuration is saved (SecretScopeValidation).
                throw new LifecycleException("Cannot store property '" + name + "' with scope 'secret': only "
                        + "valueString can be vaulted, not valueObject, valueList, valueInt, valueLong, valueFloat, valueDouble or "
                        + "valueBoolean. Refusing to persist the value in plaintext.");
            }
            value = authoredValue(instruction, name, templateDataObjects);
            if (value == null) {
                return null;
            }
        }

        if (value instanceof String text) {
            if (isScrubbedPlaceholder(name, text, scope)) {
                // Neither vaulted nor stored — see isScrubbedPlaceholder.
                return null;
            }
            value = convertToObjectIfRequested(instruction, text);
        }

        String vaulted = null;
        if (scope == Scope.secret) {
            if (!(value instanceof String plaintext)) {
                throw new LifecycleException("Cannot store property '" + name + "' with scope 'secret': only "
                        + "string values can be vaulted, but the instruction produced a " + value.getClass().getSimpleName()
                        + ". Refusing to persist the value in plaintext.");
            }
            if (plaintext.isEmpty()) {
                return null;
            }
            conversationProperties.put(name, secretPropertyVault.vault(memory, name, plaintext));
            vaulted = plaintext;
        } else {
            Property property = PropertyValues.toProperty(name, value, scope);
            if (property == null) {
                // Not a JSON value — a path into a live memory object (an output item, the
                // conversation log, a BigInteger beyond the long range). Such an instruction
                // was always a silent no-op in property.json; failing the turn now would break
                // agents that ran fine, so it is skipped, loudly.
                LOGGER.warnf("Property '%s' not set: a value of type %s fits no property slot (string, object, list, int, long, "
                        + "float, double, boolean) (conversation %s).", name, value.getClass().getName(), memory.getConversationId());
                return null;
            }
            property.setVisibility(instruction.getVisibility());
            conversationProperties.put(name, property);
        }

        templateDataObjects.put(PROPERTIES_IDENTIFIER, conversationProperties.toMap());
        return vaulted;
    }

    /**
     * The value an instruction without {@code fromObjectPath} sets: the rendered
     * {@code valueString}, or a typed field. When several are set, the last in the
     * order string, object, list, int, long, float, double, boolean wins — the
     * order in which the property setter used to write them over each other.
     */
    private Object authoredValue(PropertyInstruction instruction, String name, Map<String, Object> templateDataObjects)
            throws LifecycleException {
        Object value = null;
        String valueString = instruction.getValueString();
        if (!isNullOrEmpty(valueString)) {
            value = render(valueString, templateDataObjects, "valueString of property '" + name + "'");
        }
        if (instruction.getValueObject() != null) {
            value = instruction.getValueObject();
        }
        if (instruction.getValueList() != null) {
            value = instruction.getValueList();
        }
        if (instruction.getValueInt() != null) {
            value = instruction.getValueInt();
        }
        if (instruction.getValueLong() != null) {
            value = instruction.getValueLong();
        }
        if (instruction.getValueFloat() != null) {
            value = instruction.getValueFloat();
        }
        if (instruction.getValueDouble() != null) {
            value = instruction.getValueDouble();
        }
        if (instruction.getValueBoolean() != null) {
            value = instruction.getValueBoolean();
        }
        return value;
    }

    private Object convertToObjectIfRequested(PropertyInstruction instruction, String text) {
        if (!Boolean.TRUE.equals(instruction.getConvertToObject())) {
            return text;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return text;
        }
        try {
            Object parsed = jsonObjectParser.parse(trimmed);
            return parsed != null ? parsed : text;
        } catch (IOException e) {
            return text;
        }
    }

    private String render(String template, Map<String, Object> templateDataObjects, String what) throws LifecycleException {
        try {
            return templatingEngine.processTemplate(template, templateDataObjects);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            throw new LifecycleException("Cannot render the " + what + ": " + e.getLocalizedMessage(), e);
        }
    }

    /**
     * True when a property value resolved to a scrub placeholder instead of the
     * value it was meant to capture, which means that value had already been
     * scrubbed. This happens when a turn the client flagged {@code secretInput}
     * paused on a RULE pause and the instruction runs after the resume:
     * {@code Conversation} scrubs a secret input when the turn stops, so
     * {@code {memory.current.input}} then reads the placeholder.
     * <ul>
     * <li>Any scope: a value that IS {@code <secret input>}. Storing it would
     * silently configure the agent with that literal text.</li>
     * <li>{@code scope:"secret"}: a value that CONTAINS either placeholder
     * ({@code <secret input>} or the secret-context one). Vaulting it would
     * overwrite the stored secret with the placeholder; the plaintext is
     * deliberately not carried across a pause, so the user must submit the
     * credential again.</li>
     * </ul>
     * The property is left unset instead, and the reason logged.
     */
    static boolean isScrubbedPlaceholder(String keyName, String value, Scope scope) {
        if (value == null) {
            return false;
        }
        boolean scrubbed = MemoryKeys.SECRET_INPUT_PLACEHOLDER.equals(value.trim()) || scope == Scope.secret
                && (value.contains(MemoryKeys.SECRET_INPUT_PLACEHOLDER) || value.contains(MemoryKeys.SECRET_CONTEXT_PLACEHOLDER));
        if (scrubbed) {
            LOGGER.warnf("Property '%s' resolved to a scrub placeholder, not the value it captures: that value was already scrubbed "
                    + "(typically a capture that runs after a HITL resume of a secretInput turn). The property is not set "
                    + "and nothing is vaulted. Capture the input before the pause.", keyName);
        }
        return scrubbed;
    }
}
