/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.util.Locale;
import java.util.Map;

/**
 * The geometry a knowledge base's vectors live in: which embedding model made
 * them, and how many numbers each one has. Two vectors are only comparable when
 * they come from the same space, so this is what must match between what a
 * vector store holds and what a knowledge base is configured to write and
 * search with.
 *
 * <h2>What counts</h2>
 * <ul>
 * <li>the embedding provider and the model it is asked for — the model, or for
 * Azure OpenAI the deployment (which stands for one model);</li>
 * <li>the output size: {@code dimensions} or Gemini's
 * {@code outputDimensionality} when configured, and the size actually measured
 * from the first embedding an ingestion makes;</li>
 * <li>Gemini's {@code taskType}, which changes the vector a given text gets.
 * </li>
 * </ul>
 * Credentials, endpoints, timeouts and retries are not part of it: moving a
 * model to another host, or rotating a key, does not change a vector. No
 * embedding provider here exposes a normalisation switch; if one is added, it
 * belongs in this record.
 *
 * <p>
 * The record has a short text form ({@link #encode}) because that is what is
 * kept next to the vector store's location, and a client-safe description
 * ({@link #describe}) because the same text is shown to operators.
 *
 * @param dimension
 *            the vector size, or {@code null} while it is not known (a
 *            configuration that does not fix it, before anything was embedded)
 */
public record VectorSpace(String provider, String model, String deployment, String taskType, Integer dimension) {

    private static final String VERSION = "v1";
    private static final String DEFAULT_PROVIDER = "openai";

    public VectorSpace {
        provider = normalize(provider).isEmpty() ? DEFAULT_PROVIDER : normalize(provider);
        model = normalize(model);
        deployment = normalize(deployment);
        taskType = normalize(taskType);
        if (dimension != null && dimension <= 0) {
            dimension = null;
        }
    }

    /**
     * The space a knowledge base is configured for. {@code params} are the
     * knowledge base's embedding parameters with global variables already resolved
     * (an unresolved reference compares as the reference).
     */
    public static VectorSpace of(RagConfiguration config, Map<String, String> params) {
        Map<String, String> p = params == null ? Map.of() : params;
        Integer dimension = firstInt(p.get("dimensions"), p.get("outputDimensionality"));
        return new VectorSpace(config.getEmbeddingProvider(), EmbeddingParameters.modelName(p, ""),
                p.get("deploymentName"), p.get("taskType"), dimension);
    }

    /** The same space with a measured vector size. */
    public VectorSpace withDimension(Integer measured) {
        return new VectorSpace(provider, model, deployment, taskType, measured);
    }

    /**
     * Whether vectors of {@code other} cannot be searched together with vectors of
     * this space. A vector size that is unknown on either side is not a conflict:
     * it is checked when it becomes known.
     */
    public boolean conflictsWith(VectorSpace other) {
        if (other == null) {
            return false;
        }
        return !provider.equalsIgnoreCase(other.provider) || !model.equalsIgnoreCase(other.model)
                || !deployment.equalsIgnoreCase(other.deployment) || !taskType.equalsIgnoreCase(other.taskType)
                || (dimension != null && other.dimension != null && !dimension.equals(other.dimension));
    }

    /** A readable, secret-free description for messages shown to operators. */
    public String describe() {
        StringBuilder text = new StringBuilder(provider);
        String name = !deployment.isEmpty() ? deployment : model;
        text.append(" / ").append(name.isEmpty() ? "default model" : name);
        if (!taskType.isEmpty()) {
            text.append(" (").append(taskType).append(')');
        }
        text.append(dimension == null ? ", dimension not yet known" : ", " + dimension + " dimensions");
        return text.toString();
    }

    /** The text form that is stored. */
    public String encode() {
        return String.join("|", VERSION, provider, model, deployment, taskType, dimension == null ? "" : dimension.toString());
    }

    /**
     * Reads {@link #encode}'s output; {@code null} for anything else, including
     * text written by a later version that this one cannot read.
     */
    public static VectorSpace decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            return null;
        }
        return new VectorSpace(parts[1], parts[2], parts[3], parts[4], firstInt(parts[5], null));
    }

    private static String normalize(String value) {
        // The separator cannot occur in a stored component, and case is not
        // significant for a provider or model name.
        return value == null ? "" : value.trim().replace('|', '/').toLowerCase(Locale.ROOT);
    }

    private static Integer firstInt(String first, String second) {
        for (String candidate : new String[]{first, second}) {
            if (candidate != null && !candidate.isBlank()) {
                try {
                    int parsed = Integer.parseInt(candidate.trim());
                    return parsed > 0 ? parsed : null;
                } catch (NumberFormatException e) {
                    // Not a number — a reference that did not resolve, say. The size is
                    // simply unknown until it is measured.
                    return null;
                }
            }
        }
        return null;
    }
}
