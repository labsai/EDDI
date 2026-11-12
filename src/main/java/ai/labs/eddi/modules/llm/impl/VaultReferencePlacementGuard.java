/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.EmbeddingParameters;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.model.StoreParameters;
import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.VaultReferencePlacement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The save-time half of {@link VaultReferencePlacement}: refuses to store an
 * LLM or RAG configuration that puts a {@code ${vault:…}} reference into a
 * model parameter that does not carry a credential.
 * <p>
 * The runtime check in {@link ChatModelRegistry} and
 * {@link EmbeddingModelFactory} is the one that holds — it also sees what a
 * {@code ${vars:…}} expands to, and configurations stored before this check
 * existed. This one says so to the editor at the moment of writing, with a 400
 * naming each field, rather than at the first turn. Applied by the REST stores
 * on create, update, patch and duplicate, and by the import and upgrade paths,
 * which create documents directly.
 * <p>
 * A task whose {@code type} is not a registered provider (a {@code ${vars:…}}
 * or a template, resolved per turn) is judged against the credential keys of
 * every provider: what no provider treats as a credential is refused whatever
 * the type turns out to be, and the runtime check judges the rest.
 */
@ApplicationScoped
public class VaultReferencePlacementGuard {

    private final Map<String, Provider<ILanguageModelBuilder>> builders;

    @Inject
    public VaultReferencePlacementGuard(Map<String, Provider<ILanguageModelBuilder>> builders) {
        this.builders = builders;
    }

    /**
     * @throws BadRequestException
     *             with a JSON body naming every misplaced reference, for an
     *             {@link LlmConfiguration} or a {@link RagConfiguration}; any other
     *             document is not checked
     */
    public void requireNone(Object document) {
        List<String> problems = problemsOf(document);
        if (problems.isEmpty()) {
            return;
        }
        String message = String.join(" ", problems);
        Map<String, Object> body = new LinkedHashMap<>(ErrorResponses.body(ErrorResponses.BAD_REQUEST, message));
        body.put("problems", problems);
        throw new BadRequestException(message,
                Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build());
    }

    /**
     * Every misplaced reference in {@code document}, described; empty when none.
     */
    List<String> problemsOf(Object document) {
        List<String> problems = new ArrayList<>();
        if (document instanceof LlmConfiguration llm) {
            collect(llm, problems);
        } else if (document instanceof RagConfiguration rag) {
            String problem = EmbeddingParameters.findMisplacedVaultReference(rag.getEmbeddingProvider(), rag.getEmbeddingParameters());
            if (problem != null) {
                problems.add(problem);
            }
            String storeProblem = StoreParameters.findMisplacedVaultReference(rag.getStoreType(), rag.getStoreParameters());
            if (storeProblem != null) {
                problems.add(storeProblem);
            }
        }
        return problems;
    }

    private void collect(LlmConfiguration config, List<String> problems) {
        if (config.tasks() == null) {
            return;
        }
        for (int i = 0; i < config.tasks().size(); i++) {
            LlmConfiguration.Task task = config.tasks().get(i);
            if (task == null) {
                continue;
            }
            String path = "tasks[" + i + "]";
            check(path + ".parameters", task.getType(), modelParameters(task.getParameters()), problems);
            LlmConfiguration.ModelCascadeConfig cascade = task.getModelCascade();
            if (cascade == null) {
                continue;
            }
            if (cascade.getSteps() != null) {
                for (int s = 0; s < cascade.getSteps().size(); s++) {
                    LlmConfiguration.CascadeStep step = cascade.getSteps().get(s);
                    if (step == null) {
                        continue;
                    }
                    String type = step.getType() != null && !step.getType().isBlank() ? step.getType() : task.getType();
                    check(path + ".modelCascade.steps[" + s + "].parameters", type, step.getParameters(), problems);
                }
            }
            LlmConfiguration.JudgeModelConfig judge = cascade.getJudgeModel();
            if (judge != null) {
                check(path + ".modelCascade.judgeModel.parameters", judge.getType(), judge.getParameters(), problems);
            }
        }
    }

    /** The parameters that reach a builder: the prompts are never resolved. */
    private static Map<String, String> modelParameters(Map<String, String> parameters) {
        if (parameters == null) {
            return null;
        }
        Map<String, String> model = new HashMap<>(parameters);
        ChatModelRegistry.NON_MODEL_PARAMETERS.forEach(model::remove);
        return model;
    }

    private void check(String path, String type, Map<String, String> parameters, List<String> problems) {
        Set<String> credentials = credentialKeysOf(type);
        String key = VaultReferencePlacement.findMisplaced(parameters, credentials);
        if (key != null) {
            problems.add(VaultReferencePlacement.describe(path + " (type '" + (type == null ? "" : type.trim()) + "')", key, credentials));
        }
    }

    /**
     * The credential keys of the provider {@code type} names, or of every provider
     * when it names none of them.
     */
    Set<String> credentialKeysOf(String type) {
        Provider<ILanguageModelBuilder> provider = type == null ? null : builders.get(type.trim());
        if (provider != null) {
            return provider.get().secretParameters();
        }
        // Computed per call rather than cached: only a save with an unresolved type
        // gets here, and a builder is a cheap object to obtain.
        Set<String> union = new HashSet<>();
        for (Provider<ILanguageModelBuilder> each : builders.values()) {
            union.addAll(each.get().secretParameters());
        }
        return union;
    }
}
