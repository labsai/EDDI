/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether a change to a spending control makes spending stricter or
 * looser — the line between what an MCP client may apply itself and what an
 * {@code eddi-admin} has to approve.
 * <p>
 * <b>Conservative by construction.</b> Every rule here errs towards
 * "loosening": a change it cannot prove stricter goes to a human. A false
 * "loosening" costs one click; a false "tightening" would let a steered model
 * raise a budget. In particular:
 * <ul>
 * <li>a <b>removed</b> catalog entry is always loosening (the models it priced
 * fall back to another entry or to unpriced);</li>
 * <li>a <b>new</b> entry is loosening while the policy in force refuses
 * unpriced calls — a price admits calls that are refused today — and otherwise
 * unless its lowest price of every kind is at least the highest price of every
 * existing entry of the same provider (or of provider {@code *}): a call is
 * priced by its reported name before its requested name, so a new entry can
 * take over a call whatever the two patterns look like;</li>
 * <li>a <b>changed</b> entry is stricter only if no price goes down, no price
 * is cleared, and the long-context threshold only moves down over a tier that
 * costs at least the base price;</li>
 * <li>a quota limit counts {@code -1} (unlimited) as infinite, and
 * {@code enabled: false} switches every limit off.</li>
 * </ul>
 */
public final class SpendControlDiff {

    /** What changed, and which of those changes loosen spending. */
    public record Result(List<String> changes, List<String> loosening) {

        public boolean loosens() {
            return !loosening.isEmpty();
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    private SpendControlDiff() {
    }

    // ------------------------------------------------------------------ catalog

    /**
     * Compares two catalogs.
     *
     * @param before
     *            the stored catalog
     * @param after
     *            the catalog that would be written
     * @param policyInForce
     *            the effective unpriced policy now (the stricter of the catalog's
     *            and the deployment floor)
     */
    public static Result compareCatalogs(ModelPriceCatalog before, ModelPriceCatalog after, UnpricedPolicy policyInForce) {
        List<String> changes = new ArrayList<>();
        List<String> loosening = new ArrayList<>();

        UnpricedPolicy oldPolicy = before.unpricedPolicy();
        UnpricedPolicy newPolicy = after.unpricedPolicy();
        if (oldPolicy != newPolicy) {
            String text = "unpricedPolicy " + oldPolicy + " -> " + newPolicy;
            changes.add(text);
            if (newPolicy.ordinal() < oldPolicy.ordinal()) {
                loosening.add(text + " (refuses fewer unpriced calls)");
            }
        }

        Map<String, ModelPriceEntry> oldEntries = byKey(before.entries());
        Map<String, ModelPriceEntry> newEntries = byKey(after.entries());

        for (var old : oldEntries.entrySet()) {
            if (!newEntries.containsKey(old.getKey())) {
                String text = "removed " + label(old.getValue());
                changes.add(text);
                loosening.add(text + " (its models fall back to another entry or become unpriced)");
            }
        }
        for (var entry : newEntries.entrySet()) {
            ModelPriceEntry next = entry.getValue();
            ModelPriceEntry old = oldEntries.get(entry.getKey());
            if (old == null) {
                compareAdded(next, before.entries(), policyInForce, changes, loosening);
            } else if (!old.equals(next)) {
                compareChanged(old, next, changes, loosening);
            }
        }
        return new Result(List.copyOf(changes), List.copyOf(loosening));
    }

    private static void compareAdded(ModelPriceEntry added, List<ModelPriceEntry> existing, UnpricedPolicy policyInForce, List<String> changes,
                                     List<String> loosening) {
        String text = "added " + label(added) + " " + prices(added);
        changes.add(text);
        if (policyInForce != null && policyInForce != UnpricedPolicy.ALLOW) {
            loosening.add(text + " (under unpricedPolicy " + policyInForce
                    + " a new price admits calls to models that are refused as unpriced today)");
            return;
        }
        double[] lowest = lowest(added);
        for (ModelPriceEntry other : existing) {
            if (!mayPriceSameCall(added, other)) {
                continue;
            }
            double[] highest = highest(other);
            for (int kind = 0; kind < KINDS.length; kind++) {
                if (lowest[kind] < highest[kind]) {
                    loosening.add(text + " (" + KINDS[kind] + " " + lowest[kind] + " is below the " + highest[kind] + " of "
                            + label(other) + ", which may price the same calls today)");
                    return;
                }
            }
        }
    }

    private static void compareChanged(ModelPriceEntry old, ModelPriceEntry next, List<String> changes, List<String> loosening) {
        String name = label(next);
        List<String> details = new ArrayList<>();
        List<String> looser = new ArrayList<>();

        double[] oldBase = base(old);
        double[] newBase = base(next);
        Double[][] rawBase = {{old.inputPricePer1M(), next.inputPricePer1M()}, {old.outputPricePer1M(), next.outputPricePer1M()},
                {old.cacheReadPricePer1M(), next.cacheReadPricePer1M()}, {old.cacheWritePricePer1M(), next.cacheWritePricePer1M()}};
        boolean oldUnpriced = unpriced(old);
        boolean newUnpriced = unpriced(next);
        if (oldUnpriced != newUnpriced) {
            String text = newUnpriced ? "input and output price cleared" : "input/output price set";
            details.add(text);
            // Becoming unpriced costs $0 under ALLOW; becoming priced admits refused calls
            // under a refusing policy. Neither is provably stricter.
            looser.add(text);
        }
        for (int kind = 0; kind < KINDS.length; kind++) {
            comparePrice(KINDS[kind], rawBase[kind][0], rawBase[kind][1], oldBase[kind], newBase[kind], details, looser);
        }

        LongContextTier oldTier = old.longContext();
        LongContextTier newTier = next.longContext();
        if (!Objects.equals(oldTier, newTier)) {
            double[] oldTierPrices = tier(old, oldBase);
            double[] newTierPrices = tier(next, newBase);
            Double[][] rawTier = {{tierField(oldTier, 0), tierField(newTier, 0)}, {tierField(oldTier, 1), tierField(newTier, 1)},
                    {tierField(oldTier, 2), tierField(newTier, 2)}, {tierField(oldTier, 3), tierField(newTier, 3)}};
            for (int kind = 0; kind < KINDS.length; kind++) {
                comparePrice("long-context " + KINDS[kind], rawTier[kind][0], rawTier[kind][1], oldTierPrices[kind], newTierPrices[kind], details,
                        looser);
            }
            Long oldThreshold = oldTier == null ? null : oldTier.thresholdTokens();
            Long newThreshold = newTier == null ? null : newTier.thresholdTokens();
            if (!Objects.equals(oldThreshold, newThreshold)) {
                String text = "long-context threshold " + oldThreshold + " -> " + newThreshold;
                details.add(text);
                boolean lowered = oldThreshold != null && newThreshold != null && newThreshold < oldThreshold;
                boolean tierCostsMore = true;
                for (int kind = 0; kind < KINDS.length; kind++) {
                    tierCostsMore &= newTierPrices[kind] >= newBase[kind];
                }
                // A tier added where there was none is judged by its prices above (each
                // compared with the base it replaces); any other move must lower the
                // threshold over a tier that is at least as expensive as the base.
                boolean added = oldThreshold == null && newThreshold != null;
                if (!(added || (lowered && tierCostsMore))) {
                    looser.add(text);
                }
            }
        }
        if (!Objects.equals(old.asOf(), next.asOf()) || !Objects.equals(old.source(), next.source()) || !Objects.equals(old.note(), next.note())) {
            details.add("asOf/source/note");
        }
        if (details.isEmpty()) {
            details.add("spelling of provider/model");
        }
        String text = "changed " + name + ": " + String.join(", ", details);
        changes.add(text);
        if (!looser.isEmpty()) {
            loosening.add("changed " + name + ": " + String.join(", ", looser));
        }
    }

    private static void comparePrice(String kind, Double oldRaw, Double newRaw, double oldEffective, double newEffective, List<String> details,
                                     List<String> looser) {
        if (Objects.equals(oldRaw, newRaw) && oldEffective == newEffective) {
            return;
        }
        String text = kind + " " + display(oldRaw, oldEffective) + " -> " + display(newRaw, newEffective);
        details.add(text);
        if (oldRaw != null && newRaw == null) {
            looser.add(text + " (cleared)");
        } else if (newEffective < oldEffective) {
            looser.add(text);
        }
    }

    private static String display(Double raw, double effective) {
        return raw != null ? String.valueOf(raw) : "unset(" + effective + ")";
    }

    private static final String[] KINDS = {"input", "output", "cacheRead", "cacheWrite"};

    /**
     * Base prices per kind with the pricing fallbacks: missing side $0, cache =
     * input.
     */
    private static double[] base(ModelPriceEntry e) {
        double in = e.inputPricePer1M() != null ? e.inputPricePer1M() : 0.0;
        double out = e.outputPricePer1M() != null ? e.outputPricePer1M() : 0.0;
        double cacheRead = e.cacheReadPricePer1M() != null ? e.cacheReadPricePer1M() : in;
        double cacheWrite = e.cacheWritePricePer1M() != null ? e.cacheWritePricePer1M() : in;
        return new double[]{in, out, cacheRead, cacheWrite};
    }

    /**
     * Long-context prices per kind: an unset tier price is the base price of the
     * same kind.
     */
    private static double[] tier(ModelPriceEntry e, double[] base) {
        LongContextTier t = e.longContext();
        if (t == null) {
            return base.clone();
        }
        double[] result = new double[4];
        for (int kind = 0; kind < 4; kind++) {
            Double value = tierField(t, kind);
            result[kind] = value != null ? value : base[kind];
        }
        return result;
    }

    private static Double tierField(LongContextTier t, int kind) {
        if (t == null) {
            return null;
        }
        return switch (kind) {
            case 0 -> t.inputPricePer1M();
            case 1 -> t.outputPricePer1M();
            case 2 -> t.cacheReadPricePer1M();
            default -> t.cacheWritePricePer1M();
        };
    }

    private static double[] lowest(ModelPriceEntry e) {
        double[] base = base(e);
        double[] tier = tier(e, base);
        double[] result = new double[4];
        for (int kind = 0; kind < 4; kind++) {
            result[kind] = Math.min(base[kind], tier[kind]);
        }
        return result;
    }

    private static double[] highest(ModelPriceEntry e) {
        double[] base = base(e);
        double[] tier = tier(e, base);
        double[] result = new double[4];
        for (int kind = 0; kind < 4; kind++) {
            result[kind] = Math.max(base[kind], tier[kind]);
        }
        return result;
    }

    private static boolean unpriced(ModelPriceEntry e) {
        return e.inputPricePer1M() == null && e.outputPricePer1M() == null;
    }

    /**
     * Whether both entries could price one call — decided on the provider alone,
     * never on the model patterns. A call is priced by the name the provider
     * reported first and the requested name second
     * ({@code ModelPriceMatcher#match(String, String, String)}), and the two names
     * can be unrelated (a dated snapshot, an Azure deployment, an OpenRouter
     * route). So a new entry that matches no name an existing entry matches can
     * still take over a call that entry prices today: the call's reported name,
     * unpriced so far, now matches the new entry first. The same provider, or
     * provider {@code *} on either side, is enough.
     */
    static boolean mayPriceSameCall(ModelPriceEntry a, ModelPriceEntry b) {
        String pa = lower(a.provider());
        String pb = lower(b.provider());
        return pa.equals(pb) || "*".equals(pa) || "*".equals(pb);
    }

    /** The catalog's uniqueness key: provider and model, case-insensitively. */
    public static String key(String provider, String model) {
        return lower(provider) + "\u0000" + lower(model);
    }

    private static Map<String, ModelPriceEntry> byKey(List<ModelPriceEntry> entries) {
        Map<String, ModelPriceEntry> map = new LinkedHashMap<>();
        for (ModelPriceEntry e : entries) {
            map.put(key(e.provider(), e.model()), e);
        }
        return map;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    static String label(ModelPriceEntry e) {
        return e.provider() + "/" + e.model();
    }

    private static String prices(ModelPriceEntry e) {
        return "(input " + e.inputPricePer1M() + ", output " + e.outputPricePer1M() + ")";
    }

    // ------------------------------------------------------------------ quota

    /**
     * Compares two quotas. A limit of {@code -1} (or any negative) is unlimited; a
     * disabled quota enforces nothing.
     */
    public static Result compareQuotas(TenantQuota before, TenantQuota after) {
        List<String> changes = new ArrayList<>();
        List<String> loosening = new ArrayList<>();
        limit("maxConversationsPerDay", before.maxConversationsPerDay(), after.maxConversationsPerDay(), changes, loosening);
        limit("maxAgentsPerTenant", before.maxAgentsPerTenant(), after.maxAgentsPerTenant(), changes, loosening);
        limit("maxApiCallsPerMinute", before.maxApiCallsPerMinute(), after.maxApiCallsPerMinute(), changes, loosening);
        limit("maxMonthlyCostUsd", before.maxMonthlyCostUsd(), after.maxMonthlyCostUsd(), changes, loosening);
        if (before.enabled() != after.enabled()) {
            String text = "enabled " + before.enabled() + " -> " + after.enabled();
            changes.add(text);
            if (!after.enabled()) {
                loosening.add(text + " (no limit is enforced)");
            }
        }
        return new Result(List.copyOf(changes), List.copyOf(loosening));
    }

    private static void limit(String field, double before, double after, List<String> changes, List<String> loosening) {
        if (Double.compare(before, after) == 0) {
            return;
        }
        double oldEffective = before < 0 ? Double.POSITIVE_INFINITY : before;
        double newEffective = after < 0 ? Double.POSITIVE_INFINITY : after;
        if (oldEffective == newEffective) {
            return;
        }
        String text = field + " " + show(before) + " -> " + show(after);
        changes.add(text);
        if (newEffective > oldEffective) {
            loosening.add(text);
        }
    }

    private static String show(double value) {
        if (value < 0) {
            return "unlimited";
        }
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
