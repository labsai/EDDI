/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.semantic;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Maps an input the dictionaries did not understand to the closest example
 * sentence, by embedding similarity.
 * <p>
 * The dictionaries stay the deterministic first line: this runs only when they
 * produced nothing but {@code unknown}/{@code unused} expressions, and it
 * yields the expressions an agent designer attached to an example — so
 * behaviour rules keep matching on expressions they know, and only the recall
 * becomes fuzzy. An example that is not at least {@link #getMinScore()} similar
 * is not a match.
 * <p>
 * One instance belongs to one configured parser, which every conversation of
 * the workflow shares. The examples are embedded once, on first use (not at
 * deployment, so a provider outage cannot fail a deployment); a failed attempt
 * is retried after {@link #RETRY_AFTER_FAILURE}, not on every turn. Every
 * failure degrades to "no match" — the turn continues as if there were no
 * fallback.
 */
public class SemanticFallback {
    private static final Logger LOGGER = Logger.getLogger(SemanticFallback.class);

    /** Examples embedded per provider request. */
    static final int EMBEDDING_BATCH_SIZE = 64;
    /** Upper bound on examples, explicit and taken from dictionaries together. */
    public static final int MAX_EXAMPLES = 2000;
    /** Longest input text sent to the provider; the rest is cut off. */
    public static final int MAX_INPUT_CHARS = 1000;
    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(60);

    /**
     * One example sentence and what it means.
     *
     * @param text
     *            the sentence
     * @param expressions
     *            the expressions an input like it yields, in expression notation
     * @param languageCode
     *            the language it is written in; {@code null} for every language
     */
    public record Example(String text, String expressions, String languageCode) {
    }

    /**
     * The example the input is closest to.
     *
     * @param score
     *            cosine similarity, at least {@link #getMinScore()}
     */
    public record Match(Example example, double score) {
    }

    private final Supplier<EmbeddingModel> model;
    private final List<Example> examples;
    private final double minScore;
    private final LongSupplier clock;

    private volatile List<Embedding> exampleEmbeddings;
    private volatile boolean backingOff;
    private volatile long retryNotBefore;

    public SemanticFallback(Supplier<EmbeddingModel> model, List<Example> examples, double minScore) {
        this(model, examples, minScore, System::nanoTime);
    }

    SemanticFallback(Supplier<EmbeddingModel> model, List<Example> examples, double minScore, LongSupplier clock) {
        this.model = model;
        this.examples = List.copyOf(examples);
        this.minScore = minScore;
        this.clock = clock;
    }

    public double getMinScore() {
        return minScore;
    }

    public List<Example> getExamples() {
        return examples;
    }

    /**
     * @param input
     *            the normalized user input
     * @param userLanguage
     *            the conversation language; examples of other languages are skipped
     * @return the best example at or above the minimum score, or empty — also when
     *         the provider failed or the thread was interrupted
     */
    public Optional<Match> classify(String input, String userLanguage) {
        if (isNullOrEmpty(input) || examples.isEmpty() || Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }

        List<Embedding> embedded = exampleEmbeddings();
        if (embedded == null) {
            return Optional.empty();
        }

        Embedding query;
        try {
            String text = input.length() > MAX_INPUT_CHARS ? input.substring(0, MAX_INPUT_CHARS) : input;
            query = model.get().embed(text).content();
        } catch (RuntimeException e) {
            LOGGER.warnf("Semantic parser fallback could not embed the input: %s", e.getMessage());
            return Optional.empty();
        }
        if (query == null) {
            return Optional.empty();
        }

        Match best = null;
        for (int i = 0; i < examples.size(); i++) {
            Example example = examples.get(i);
            if (!appliesToLanguage(example.languageCode(), userLanguage)) {
                continue;
            }
            double score = CosineSimilarity.between(query, embedded.get(i));
            if (score >= minScore && (best == null || score > best.score())) {
                best = new Match(example, score);
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean appliesToLanguage(String exampleLanguage, String userLanguage) {
        return isNullOrEmpty(exampleLanguage) || isNullOrEmpty(userLanguage) || exampleLanguage.equals(userLanguage);
    }

    /**
     * The example vectors, embedded on first use; {@code null} while unavailable.
     * Synchronized so that concurrent first turns embed the examples once.
     */
    private List<Embedding> exampleEmbeddings() {
        List<Embedding> embedded = exampleEmbeddings;
        if (embedded != null) {
            return embedded;
        }
        synchronized (this) {
            if (exampleEmbeddings != null) {
                return exampleEmbeddings;
            }
            // nanoTime has an arbitrary origin: compare differences, and only once a
            // failure actually set a deadline
            if (backingOff && clock.getAsLong() - retryNotBefore < 0) {
                return null;
            }
            try {
                EmbeddingModel embeddingModel = model.get();
                List<Embedding> built = new ArrayList<>(examples.size());
                for (int from = 0; from < examples.size(); from += EMBEDDING_BATCH_SIZE) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    List<TextSegment> batch = examples.subList(from, Math.min(examples.size(), from + EMBEDDING_BATCH_SIZE)).stream()
                            .map(example -> TextSegment.from(example.text())).toList();
                    List<Embedding> vectors = embeddingModel.embedAll(batch).content();
                    if (vectors == null || vectors.size() != batch.size()) {
                        throw new IllegalStateException("the provider returned " + (vectors == null ? "no" : vectors.size()) + " embeddings for "
                                + batch.size() + " examples");
                    }
                    built.addAll(vectors);
                }
                exampleEmbeddings = List.copyOf(built);
                return exampleEmbeddings;
            } catch (RuntimeException e) {
                retryNotBefore = clock.getAsLong() + RETRY_AFTER_FAILURE.toNanos();
                backingOff = true;
                LOGGER.warnf("Semantic parser fallback could not embed its %d examples, retrying in %ds: %s", examples.size(),
                        RETRY_AFTER_FAILURE.toSeconds(), e.getMessage());
                return null;
            }
        }
    }
}
