/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the catalog entry that prices a model. Immutable and precompiled: exact
 * names sit in a hash map, globs in a list sorted by the length of their
 * literal part, so a lookup is a map probe plus a short scan and never does I/O
 * or regex work (a glob is only a leading and/or trailing {@code *}, which
 * keeps the matcher free of ReDoS by construction).
 * <p>
 * Precedence, for one model name:
 * <ol>
 * <li>an entry for this provider whose model is the exact name;</li>
 * <li>an entry for this provider whose glob has the longest literal part —
 * {@code gpt-4o-mini*} beats {@code gpt-4o*};</li>
 * <li>the same two steps over entries for provider {@code *}.</li>
 * </ol>
 * Names compare case-insensitively. {@link #match(String, String, String)}
 * tries the name the provider reported first (OpenAI's dated snapshots, the
 * model behind an Azure deployment, the model OpenRouter routed to) and the
 * requested name second.
 */
public final class ModelPriceMatcher {

    /** Matches nothing. */
    public static final ModelPriceMatcher EMPTY = compile(List.of());

    static final String ANY = "*";

    private final Map<String, Map<String, ModelPriceEntry>> exact;
    private final Map<String, List<Glob>> globs;
    private final int size;

    private ModelPriceMatcher(Map<String, Map<String, ModelPriceEntry>> exact, Map<String, List<Glob>> globs, int size) {
        this.exact = exact;
        this.globs = globs;
        this.size = size;
    }

    /**
     * Compiles entries; entries with a blank provider or model are skipped (the
     * validator rejects them before they are ever stored). On a duplicate the first
     * entry wins.
     */
    public static ModelPriceMatcher compile(List<ModelPriceEntry> entries) {
        Map<String, Map<String, ModelPriceEntry>> exact = new HashMap<>();
        Map<String, List<Glob>> globs = new HashMap<>();
        int size = 0;
        int order = 0;
        for (ModelPriceEntry entry : entries == null ? List.<ModelPriceEntry>of() : entries) {
            if (entry == null || isBlank(entry.provider()) || isBlank(entry.model())) {
                continue;
            }
            String provider = entry.provider().trim().toLowerCase(Locale.ROOT);
            String model = entry.model().trim().toLowerCase(Locale.ROOT);
            boolean leading = model.startsWith(ANY);
            boolean trailing = model.length() > 1 && model.endsWith(ANY);
            if (!leading && !trailing) {
                exact.computeIfAbsent(provider, p -> new HashMap<>()).putIfAbsent(model, entry);
            } else {
                String literal = model.substring(leading ? 1 : 0, trailing ? model.length() - 1 : model.length());
                globs.computeIfAbsent(provider, p -> new ArrayList<>()).add(new Glob(literal, leading, trailing, order++, entry));
            }
            size++;
        }
        Comparator<Glob> byLiteral = Comparator.comparingInt((Glob g) -> g.literal.length()).reversed().thenComparingInt(g -> g.order);
        Map<String, List<Glob>> sorted = new HashMap<>();
        globs.forEach((provider, list) -> sorted.put(provider, list.stream().sorted(byLiteral).toList()));
        Map<String, Map<String, ModelPriceEntry>> frozen = new HashMap<>();
        exact.forEach((provider, map) -> frozen.put(provider, Map.copyOf(map)));
        return new ModelPriceMatcher(Map.copyOf(frozen), Map.copyOf(sorted), size);
    }

    /** Number of compiled entries. */
    public int size() {
        return size;
    }

    /**
     * The entry pricing {@code responseModel} if one matches, else the one pricing
     * {@code requestModel}.
     */
    public Optional<ModelPriceEntry> match(String provider, String responseModel, String requestModel) {
        Optional<ModelPriceEntry> byResponse = match(provider, responseModel);
        return byResponse.isPresent() ? byResponse : match(provider, requestModel);
    }

    /** The entry pricing {@code model} on {@code provider}, if any. */
    public Optional<ModelPriceEntry> match(String provider, String model) {
        if (isBlank(model) || size == 0) {
            return Optional.empty();
        }
        String name = model.trim().toLowerCase(Locale.ROOT);
        String type = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        ModelPriceEntry found = matchProvider(type, name);
        if (found == null && !ANY.equals(type)) {
            found = matchProvider(ANY, name);
        }
        return Optional.ofNullable(found);
    }

    private ModelPriceEntry matchProvider(String provider, String name) {
        Map<String, ModelPriceEntry> byName = exact.get(provider);
        if (byName != null) {
            ModelPriceEntry hit = byName.get(name);
            if (hit != null) {
                return hit;
            }
        }
        List<Glob> candidates = globs.get(provider);
        if (candidates != null) {
            for (Glob glob : candidates) {
                if (glob.matches(name)) {
                    return glob.entry;
                }
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record Glob(String literal, boolean leading, boolean trailing, int order, ModelPriceEntry entry) {
        boolean matches(String name) {
            if (leading && trailing) {
                return name.contains(literal);
            }
            return leading ? name.endsWith(literal) : name.startsWith(literal);
        }
    }
}
