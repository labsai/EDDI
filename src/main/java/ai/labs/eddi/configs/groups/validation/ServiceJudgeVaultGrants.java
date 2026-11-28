/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.secrets.model.ReferenceScanner;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The grant rule for vault references in a SERVICE judge's
 * {@code service.parameters} and in the lesson consolidation model's
 * {@code lessonConfig.consolidation.parameters}: both are resolved for a group,
 * not an agent.
 * <p>
 * A group configuration is not an agent, so the deploy-time
 * {@link VaultGrantChecker} has no identity to test a secret's
 * {@code allowedAgents} against. Without a rule, an editor could save
 * {@code apiKey: ${vault:someone-elses-key}} together with a {@code baseUrl}
 * they chose, and the judge would send that key there. The rule is therefore
 * the strictest one that needs no identity: a secret may be used by a judge
 * only if its grant is open to every agent (contains {@code "*"}, which is also
 * how a legacy secret with no grant list reads — see
 * {@link SecretMetadata#grantsAllAgents}). A secret that does not exist, or
 * whose metadata cannot be read, is refused: there is no grant to read, and the
 * judge must not guess.
 * <p>
 * Only metadata is read; the secret's value is never resolved here. Checked at
 * group save ({@code AgentGroupStore}) and again just before the judge resolves
 * its parameters, so a grant narrowed after the save is caught.
 * {@code ${vars:...}} references are not followed: only an admin can store a
 * global variable that resolves to a secret ({@code RestGlobalVariableStore}).
 */
@ApplicationScoped
public class ServiceJudgeVaultGrants {

    private static final Logger LOGGER = Logger.getLogger(ServiceJudgeVaultGrants.class);

    private final ISecretProvider secretProvider;

    @Inject
    public ServiceJudgeVaultGrants(ISecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    /**
     * The problems with the vault references in {@code parameters}, one per
     * offending parameter, each naming the parameter and what to do. Never contains
     * a value.
     *
     * @param path
     *            the config path to name in messages, e.g.
     *            {@code phases[2].convergence.service.parameters}
     */
    public List<String> problems(String path, Map<String, String> parameters) {
        List<String> problems = new ArrayList<>();
        if (parameters == null) {
            return problems;
        }
        parameters.forEach((key, value) -> {
            String problem = problemFor(value);
            if (problem != null) {
                problems.add(path + "." + key + " " + problem);
            }
        });
        return problems;
    }

    /**
     * The first problem among {@code parameters}, for the run-time re-check, or
     * empty when every vault reference is granted to all agents.
     */
    public Optional<String> firstProblem(Map<String, String> parameters) {
        List<String> problems = problems("service.parameters", parameters);
        return problems.isEmpty() ? Optional.empty() : Optional.of(problems.getFirst());
    }

    private String problemFor(String value) {
        if (value == null) {
            return null;
        }
        for (ReferenceScanner.Match match : SecretReference.scanner().findAll(value)) {
            SecretReference reference = SecretReference.of(match);
            String name = LogSanitizer.sanitize(reference.toReferenceString());
            try {
                SecretMetadata metadata = secretProvider.getMetadata(reference);
                if (metadata == null || !SecretMetadata.grantsAllAgents(metadata.allowedAgents())) {
                    return "references vault secret " + name + ", which is not granted to all agents — a group is not an agent, so "
                            + "a group-level model or action (SERVICE judge, lesson consolidation, approved-result action) may only use a secret whose grant is open to "
                            + "every agent: grant it to all agents "
                            + "(allowedAgents [\"*\"]) first";
                }
            } catch (SecretNotFoundException e) {
                return "references vault secret " + name + ", which does not exist — create it and grant it to all agents "
                        + "(allowedAgents [\"*\"]) first";
            } catch (Exception e) {
                LOGGER.debugf("Could not read grant metadata for %s: %s", name, e.getClass().getSimpleName());
                return "references vault secret " + name + ", whose grant could not be verified — grant it to all agents "
                        + "(allowedAgents [\"*\"]) and make sure the vault is available";
            }
        }
        return null;
    }
}
