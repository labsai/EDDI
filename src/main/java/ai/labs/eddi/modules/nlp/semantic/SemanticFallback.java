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
 * the workflow shares. The examples are embedded on first use (not at
 * deployment, so a provider outage cannot fail a deployment) and again whenever
 * the supplier hands out a different model instance — the factory builds a new
 * one when a secret or global variable it depends on changes, and the old
 * vectors then belong to another embedding space. A failed attempt is retried
 * after {@link #RETRY_AFTER_FAILURE}, not on every turn. Every failure degrades
 * to "no match" — the turn continues as if there were no fallback.
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

    /** The example vectors and the model instance that produced them. */
    private record EmbeddedExamples(EmbeddingModel model, List<Embedding> vectors) {
    }

    private volatile EmbeddedExamples exampleEmbeddings;
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

        EmbeddedExamples embedded = exampleEmbeddings();
        if (embedded == null) {
            return Optional.empty();
        }

        Embedding query;
        try {
            String text = input.length() > MAX_INPUT_CHARS ? input.substring(0, MAX_INPUT_CHARS) : input;
            // the model that embedded the examples: input and examples share one space
            query = embedded.model().embed(text).content();
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
            double score;
            try {
                score = CosineSimilarity.between(query, embedded.vectors().get(i));
            } catch (IllegalArgumentException e) {
                // vectors of different length: a provider that changed its model under
                // the same configuration
                LOGGER.warnf("Semantic parser fallback could not compare the input with its examples: %s", e.getMessage());
                exampleEmbeddings = null;
                return Optional.empty();
            }
            if (score >= minScore && (best == null || score > best.score())) {
                best = new Match(example, score);
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean appliesToLanguage(String exampleLanguage, String userLanguage) {
        // Only an example without a language applies to every conversation; a turn
        // without a language matches no language-specific example.
        return isNullOrEmpty(exampleLanguage) || exampleLanguage.equals(userLanguage);
    }

    /**
     * The example vectors of the current model, embedded on first use and again
     * when the model changes; {@code null} while unavailable. Synchronized so that
     * concurrent turns embed the examples once.
     */
    private EmbeddedExamples exampleEmbeddings() {
        EmbeddedExamples embedded = exampleEmbeddings;
        if (embedded != null && isCurrent(embedded)) {
            return embedded;
        }
        synchronized (this) {
            embedded = exampleEmbeddings;
            if (embedded != null && isCurrent(embedded)) {
                return embedded;
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
                embedded = new EmbeddedExamples(embeddingModel, List.copyOf(built));
                exampleEmbeddings = embedded;
                backingOff = false;
                return embedded;
            } catch (RuntimeException e) {
                exampleEmbeddings = null;
                retryNotBefore = clock.getAsLong() + RETRY_AFTER_FAILURE.toNanos();
                backingOff = true;
                LOGGER.warnf("Semantic parser fallback could not embed its %d examples, retrying in %ds: %s", examples.size(),
                        RETRY_AFTER_FAILURE.toSeconds(), e.getMessage());
                return null;
            }
        }
    }

    /**
     * Whether the supplier still hands out the model that embedded the examples. A
     * supplier failure counts as a change: the rebuild then fails and backs off.
     */
    private boolean isCurrent(EmbeddedExamples embedded) {
        try {
            return model.get() == embedded.model();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
