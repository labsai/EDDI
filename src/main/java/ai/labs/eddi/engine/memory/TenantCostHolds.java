/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * What the current turn of one conversation holds against the tenant's monthly
 * cost budget (the opt-in hard ceiling,
 * {@code eddi.tenant.quota.cost-reservation.enabled}).
 * <p>
 * Each paid call that passed the reservation gate adds a hold for its maximum
 * cost. A hold is shrunk to the actual cost when the task that made it is done,
 * and what is left is given back when the turn is charged to the tenant's
 * counter, so spend is never unaccounted for between the call and the charge.
 * <p>
 * Transient and per loaded memory, like {@link SpendReservations}: a turn's
 * calls all run on the node that holds the conversation. A node that dies with
 * holds open loses this object; the tenant counter drops such holds at their
 * TTL. Thread-safe; every method is short and holds only this object's monitor.
 */
public final class TenantCostHolds {

    /** One hold: how much, and in which counter epoch it was made. */
    public record Hold(long generation, double amountUsd) {
    }

    private final List<Hold> holds = new ArrayList<>();

    /** Records a hold the tenant counter has accepted. */
    public synchronized void add(long generation, double amountUsd) {
        if (amountUsd > 0.0 && Double.isFinite(amountUsd)) {
            holds.add(new Hold(generation, amountUsd));
        }
    }

    /** Total held by this turn. */
    public synchronized double heldUsd() {
        double sum = 0.0;
        for (Hold hold : holds) {
            sum += hold.amountUsd();
        }
        return sum;
    }

    /**
     * Lowers the holds so that no more than {@code keepUsd} remains, newest first,
     * and returns what must be given back to the tenant counter. Never raises a
     * hold.
     */
    public synchronized List<Hold> shrinkTo(double keepUsd) {
        double excess = heldUsd() - Math.max(0.0, Double.isFinite(keepUsd) ? keepUsd : 0.0);
        List<Hold> release = new ArrayList<>();
        for (int i = holds.size() - 1; i >= 0 && excess > 1e-12; i--) {
            Hold hold = holds.get(i);
            double give = Math.min(hold.amountUsd(), excess);
            release.add(new Hold(hold.generation(), give));
            excess -= give;
            if (give >= hold.amountUsd()) {
                holds.remove(i);
            } else {
                holds.set(i, new Hold(hold.generation(), hold.amountUsd() - give));
            }
        }
        return release;
    }

    /** Empties the holds and returns them, to be given back. */
    public synchronized List<Hold> drain() {
        List<Hold> all = new ArrayList<>(holds);
        holds.clear();
        return all;
    }
}
