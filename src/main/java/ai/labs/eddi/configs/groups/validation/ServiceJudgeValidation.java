/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.ServiceJudgeConfig;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Save-time rules for a phase's {@code convergence.judge} and its
 * {@code service} block.
 * <p>
 * <b>Rejected</b> (new fields only, so no stored config can start failing):
 * <ul>
 * <li>a plaintext credential in {@code service.parameters} — a credential-named
 * key ({@code apiKey}, {@code secretKey}, {@code accessKey}, {@code token},
 * ...) must hold a {@code ${vault:...}} or {@code ${vars:...}} reference, so a
 * group config cannot become a plaintext key store. The classifier is
 * {@link ConfigSecretRedactor}'s, the same one that redacts stored
 * configs;</li>
 * <li>a {@code ${connection:...}} reference in {@code service.parameters} — the
 * model registry refuses those for model parameters at call time, and finding
 * out at save time beats a judge that silently never converges;</li>
 * <li>a {@code service} block that names only one of {@code llmProvider} /
 * {@code llmModel}.</li>
 * </ul>
 * <b>Warned</b> (configs that saved before keep saving): an unknown
 * {@code judge} value, which runs as MODERATOR, and {@code judge: SERVICE}
 * without a model, which falls back to the moderator agent.
 */
public final class ServiceJudgeValidation {

    private static final Logger LOGGER = Logger.getLogger(ServiceJudgeValidation.class);

    private static final String CONNECTION_REFERENCE = "${connection:";

    private static final String[] REFERENCE_PREFIXES = {"${vault:", "${eddivault:", "${vars:"};

    private ServiceJudgeValidation() {
    }

    /**
     * The problems with one phase's convergence config, for the caller to reject
     * with. Logs the warn-only findings as a side effect.
     *
     * @param path
     *            the config path to name in messages, e.g.
     *            {@code phases[2].convergence}
     * @param groupName
     *            the group's name, for the warnings
     */
    public static List<String> problems(String path, ConvergenceConfig convergence, String groupName) {
        List<String> problems = new ArrayList<>();
        if (convergence == null) {
            return problems;
        }
        String judge = convergence.judge();
        if (!ConvergenceConfig.JUDGE_MODERATOR.equals(judge) && !ConvergenceConfig.JUDGE_SERVICE.equals(judge)) {
            LOGGER.warnf("Group '%s' %s.judge is '%s', which is neither MODERATOR nor SERVICE — it runs as MODERATOR",
                    LogSanitizer.sanitize(groupName), path, LogSanitizer.sanitize(judge));
        }
        ServiceJudgeConfig service = convergence.service();
        if (service != null) {
            if ((service.llmProvider() == null) != (service.llmModel() == null)) {
                problems.add(path + ".service needs both llmProvider and llmModel, or neither");
            }
            problems.addAll(parameterProblems(path + ".service.parameters", service.parameters()));
        }
        if (convergence.enabled() && ConvergenceConfig.JUDGE_SERVICE.equals(judge) && (service == null || !service.isConfigured())) {
            LOGGER.warnf("Group '%s' %s sets judge=SERVICE without service.llmProvider/llmModel — the moderator agent will "
                    + "judge instead", LogSanitizer.sanitize(groupName), path);
        }
        return problems;
    }

    /**
     * Credential and connection-reference problems in a judge's parameter map. The
     * value is never part of a message.
     */
    static List<String> parameterProblems(String path, Map<String, String> parameters) {
        List<String> problems = new ArrayList<>();
        if (parameters == null || parameters.isEmpty()) {
            return problems;
        }
        for (String secretPath : plaintextCredentialKeys(parameters)) {
            problems.add(plaintextProblem(path, secretPath));
        }
        parameters.forEach((key, value) -> {
            if (value != null && value.contains(CONNECTION_REFERENCE)) {
                problems.add(path + "." + key + " uses a ${connection:...} reference, which model parameters do not accept — "
                        + "use ${vault:...} or ${vars:...}");
            }
        });
        return problems;
    }

    /**
     * What a save must refuse in a model-parameter map — for
     * {@code lessonConfig.consolidation.parameters}, which took any value before
     * these rules existed: a plaintext credential, and a {@code ${connection:...}}
     * reference, which the model registry refuses for model parameters at call time
     * (the judge's rule, {@link #parameterProblems}). A value identical to the one
     * {@code stored} holds under the same key is carried over, not new, and is let
     * through: refusing it would make every stored group that has one unsavable.
     * Only an administrator can send a literal back unchanged (anyone else reads
     * the marker in its place, which {@code RedactionMarkerGuard} refuses). The
     * value is never part of a message.
     *
     * @param stored
     *            the same map in the version being replaced, or an empty map for a
     *            create
     */
    public static List<String> newModelParameterProblems(String path, Map<String, String> parameters, Map<String, String> stored) {
        List<String> problems = new ArrayList<>();
        if (parameters == null || parameters.isEmpty()) {
            return problems;
        }
        Map<String, String> before = stored == null ? Map.of() : stored;
        for (String key : plaintextCredentialKeys(parameters)) {
            String value = parameters.get(key);
            if (value == null || !value.equals(before.get(key))) {
                problems.add(plaintextProblem(path, key));
            }
        }
        parameters.forEach((key, value) -> {
            if (value != null && value.contains(CONNECTION_REFERENCE) && !value.equals(before.get(key))) {
                problems.add(path + "." + key + " uses a ${connection:...} reference, which model parameters do not accept — "
                        + "use ${vault:...} or ${vars:...}");
            }
        });
        return problems;
    }

    /**
     * The keys of {@code parameters} holding a plaintext credential: the shared
     * redactor's rule, plus any key ending in {@code key}.
     */
    private static Set<String> plaintextCredentialKeys(Map<String, String> parameters) {
        var secretPaths = new LinkedHashSet<>(ConfigSecretRedactor.plaintextSecretPaths(parameters, Set.of()));
        // Model builders name their credentials *Key (accessKey, secretAccessKey,
        // subscriptionKey); the shared redactor does not treat every one of those
        // as a credential, so this map is held to the stricter rule.
        parameters.forEach((key, value) -> {
            if (value != null && key.toLowerCase(Locale.ROOT).endsWith("key") && !isReference(value)
                    && !value.contains(CONNECTION_REFERENCE)) {
                secretPaths.add(key);
            }
        });
        return secretPaths;
    }

    private static String plaintextProblem(String path, String key) {
        return path + "." + key + " holds a plaintext credential — store it in the vault and reference it as "
                + "${vault:...} (or ${vars:...})";
    }

    /**
     * Whether a value is exactly one vault or global-variable reference.
     */
    static boolean isReference(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (!trimmed.endsWith("}") || trimmed.indexOf('}') != trimmed.length() - 1) {
            return false;
        }
        for (String prefix : REFERENCE_PREFIXES) {
            // A reference must name something: "${vault:}" never resolves and "${vars:}" is
            // left unchanged, so either would reach the judge as a literal.
            if (trimmed.startsWith(prefix) && !trimmed.substring(prefix.length(), trimmed.length() - 1).isBlank()) {
                return true;
            }
        }
        return false;
    }
}
