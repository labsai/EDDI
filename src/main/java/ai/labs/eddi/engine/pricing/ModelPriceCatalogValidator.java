/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a price catalog before it is stored. The catalog is a budget-control
 * surface — lowering a price to $0 is the same as raising a budget — so every
 * field is bounded and anything malformed is refused with a list of violations
 * rather than partly applied.
 */
public final class ModelPriceCatalogValidator {

    /** At most this many entries. */
    public static final int MAX_ENTRIES = 500;
    /** Prices are finite and at most this many USD per 1M tokens. */
    public static final double MAX_PRICE = 10_000;
    public static final long MIN_THRESHOLD = 1_000;
    public static final long MAX_THRESHOLD = 10_000_000;
    public static final int MAX_TEXT = 512;
    /** The largest stored document, in UTF-8 bytes of its JSON form. */
    public static final int MAX_DOCUMENT_BYTES = 256 * 1024;

    /**
     * 1–128 characters of a model-name alphabet, with an optional leading and/or
     * trailing {@code *} and none in the middle.
     */
    private static final Pattern MODEL = Pattern.compile("\\*?[A-Za-z0-9._:/@+-]*\\*?");
    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9._-]{1,64}");
    private static final Pattern AS_OF = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private ModelPriceCatalogValidator() {
    }

    /**
     * @param knownProviders
     *            the EDDI model types this deployment can build; null accepts any
     *            provider id of the right shape
     * @return the violations, empty when the catalog is valid
     */
    public static List<String> validate(ModelPriceCatalog catalog, Set<String> knownProviders) {
        List<String> violations = new ArrayList<>();
        if (catalog == null) {
            violations.add("the catalog is missing");
            return violations;
        }
        List<ModelPriceEntry> entries = catalog.entries();
        if (entries.size() > MAX_ENTRIES) {
            violations.add("at most " + MAX_ENTRIES + " entries are allowed, got " + entries.size());
            return violations;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            ModelPriceEntry entry = entries.get(i);
            String at = "entries[" + i + "]";
            if (entry == null) {
                violations.add(at + ": empty entry");
                continue;
            }
            validateEntry(entry, at, knownProviders, violations);
            if (entry.provider() != null && entry.model() != null) {
                String key = entry.provider().trim().toLowerCase(Locale.ROOT) + "\u0000" + entry.model().trim().toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    violations.add(at + ": duplicate entry for provider '" + entry.provider() + "' and model '" + entry.model() + "'");
                }
            }
        }
        return violations;
    }

    private static void validateEntry(ModelPriceEntry entry, String at, Set<String> knownProviders, List<String> violations) {
        String provider = entry.provider();
        if (provider == null || provider.isBlank()) {
            violations.add(at + ".provider is required");
        } else if (!ModelPriceMatcher.ANY.equals(provider)) {
            if (!PROVIDER.matcher(provider).matches()) {
                violations.add(at + ".provider must be a model type such as 'openai' or '*'");
            } else if (knownProviders != null && !knownProviders.contains(provider)) {
                violations.add(at + ".provider '" + provider + "' is not a model type of this deployment");
            }
        }
        String model = entry.model();
        if (model == null || model.isEmpty() || model.length() > 128) {
            violations.add(at + ".model must be 1-128 characters");
        } else if (!MODEL.matcher(model).matches() || "**".equals(model)) {
            violations.add(at + ".model may contain letters, digits and ._:/@+- with '*' only as a leading or trailing wildcard");
        }
        price(entry.inputPricePer1M(), at + ".inputPricePer1M", violations);
        price(entry.outputPricePer1M(), at + ".outputPricePer1M", violations);
        price(entry.cacheReadPricePer1M(), at + ".cacheReadPricePer1M", violations);
        price(entry.cacheWritePricePer1M(), at + ".cacheWritePricePer1M", violations);
        if (entry.inputPricePer1M() == null && entry.outputPricePer1M() == null) {
            violations.add(at + ": set inputPricePer1M or outputPricePer1M (0 marks a free model)");
        }
        LongContextTier tier = entry.longContext();
        if (tier != null) {
            String t = at + ".longContext";
            if (tier.thresholdTokens() == null || tier.thresholdTokens() < MIN_THRESHOLD || tier.thresholdTokens() > MAX_THRESHOLD) {
                violations.add(t + ".thresholdTokens must be between " + MIN_THRESHOLD + " and " + MAX_THRESHOLD);
            }
            price(tier.inputPricePer1M(), t + ".inputPricePer1M", violations);
            price(tier.outputPricePer1M(), t + ".outputPricePer1M", violations);
            price(tier.cacheReadPricePer1M(), t + ".cacheReadPricePer1M", violations);
            price(tier.cacheWritePricePer1M(), t + ".cacheWritePricePer1M", violations);
        }
        text(entry.note(), at + ".note", violations);
        text(entry.source(), at + ".source", violations);
        if (entry.source() != null && !entry.source().isBlank() && !isHttpUrl(entry.source())) {
            violations.add(at + ".source must be an http(s) link");
        }
        if (entry.asOf() != null && !entry.asOf().isBlank() && !AS_OF.matcher(entry.asOf()).matches()) {
            violations.add(at + ".asOf must be a date (YYYY-MM-DD)");
        }
    }

    /** A price is absent, or finite and within [0, MAX_PRICE]. */
    public static void price(Double value, String field, List<String> violations) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value < 0 || value > MAX_PRICE) {
            violations.add(field + " must be a number between 0 and " + (long) MAX_PRICE);
        }
    }

    private static void text(String value, String field, List<String> violations) {
        if (value != null && value.length() > MAX_TEXT) {
            violations.add(field + " must be at most " + MAX_TEXT + " characters");
        }
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
