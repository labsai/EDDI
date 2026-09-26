/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;

import java.util.List;

/**
 * Save-time check of {@code scope: "secret"} property instructions — in a
 * property setter, and in the pre-request / post-response instructions of
 * httpcalls, MCP calls and LLM tasks.
 * <p>
 * Only a string can be vaulted. An instruction that can only ever produce
 * something else is rejected here rather than when a conversation runs it:
 * <ul>
 * <li>{@code valueObject}, {@code valueList}, {@code valueInt},
 * {@code valueFloat} or {@code valueBoolean} under {@code scope: "secret"} —
 * those used to be stored as plaintext properties, scope notwithstanding;</li>
 * <li>{@code convertToObject: true}, which turns the value into a map;</li>
 * <li>a literal property name the vault reference cannot carry ({@code /},
 * braces, {@code $}, whitespace).</li>
 * </ul>
 * A {@code fromObjectPath} instruction is accepted: what it yields is only
 * known at run time, where {@link SecretPropertyVault} refuses anything that is
 * not a string. Throws {@link IllegalArgumentException}, which the stores turn
 * into a 400.
 */
public final class SecretScopeValidation {

    private SecretScopeValidation() {
    }

    /**
     * @param where
     *            the location for the message, e.g.
     *            {@code "httpCalls[0].postResponse"}
     */
    public static void validate(List<? extends PropertyInstruction> instructions, String where) {
        if (instructions == null) {
            return;
        }
        for (int i = 0; i < instructions.size(); i++) {
            PropertyInstruction instruction = instructions.get(i);
            if (instruction == null || instruction.getScope() != Property.Scope.secret) {
                continue;
            }
            String location = where + "[" + i + "]" + (instruction.getName() != null ? " ('" + instruction.getName() + "')" : "");
            if (instruction.getValueObject() != null || instruction.getValueList() != null || instruction.getValueInt() != null
                    || instruction.getValueFloat() != null || instruction.getValueBoolean() != null) {
                throw new IllegalArgumentException(location + " has scope 'secret' but sets valueObject, valueList, valueInt, valueFloat or "
                        + "valueBoolean. Only a string value can be vaulted — use valueString or fromObjectPath.");
            }
            if (Boolean.TRUE.equals(instruction.getConvertToObject())) {
                throw new IllegalArgumentException(location + " has scope 'secret' and convertToObject: true. Only a string value can be "
                        + "vaulted — drop convertToObject.");
            }
            String name = instruction.getName();
            if (name != null && !name.contains("{") && !SecretPropertyVault.isEmbeddableName(name)) {
                throw new IllegalArgumentException(location + " has scope 'secret' but its name cannot be part of a vault reference: it "
                        + "must not contain '/', '{', '}', '$' or whitespace.");
            }
        }
    }

    public static void validate(PreRequest preRequest, String where) {
        if (preRequest != null) {
            validate(preRequest.getPropertyInstructions(), where + ".propertyInstructions");
        }
    }

    public static void validate(PostResponse postResponse, String where) {
        if (postResponse != null) {
            validate(postResponse.getPropertyInstructions(), where + ".propertyInstructions");
        }
    }
}
