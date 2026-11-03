/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;

/**
 * What a root discussion has already charged to its team budget, and the
 * ceiling that follows from it (F6). Persisted on the {@link GroupConversation}
 * so a pause and resume neither re-charges nor forgets the ceiling.
 * <p>
 * All amounts are in the discussion's own {@code totalCost} terms, which is why
 * the existing ceiling machinery can enforce {@link #ceilingUsd} unchanged.
 */
public class TeamBudgetState {

    /** The part of {@code totalCost} already charged to the team. */
    private double chargedUsd;

    /** Charged-to-be spend a store outage held back; sent with the next charge. */
    private double pendingUsd;

    /**
     * The team (and tenant) limit expressed as a {@code totalCost} ceiling, or
     * {@code null} when this discussion's group has no team budget.
     */
    private Double ceilingUsd;

    /** What to do when {@link #ceilingUsd} binds; resolved at leg start. */
    private CostPolicy policy;

    /**
     * The team's effective monthly cap (base + adjustment) as read at leg start;
     * {@code null} when the group has no budget.
     */
    private Double capUsd;

    /**
     * The tenant's remaining budget as a {@code totalCost} ceiling, measured at leg
     * start; {@code null} when no tenant budget is in force.
     */
    private Double tenantCeilingUsd;

    /** The UTC month the charges belong to, {@code YYYY-MM}. */
    private String month;

    /** {@code TEAM} or {@code TENANT}: which limit {@link #ceilingUsd} reflects. */
    private String limitedBy;

    public double getChargedUsd() {
        return chargedUsd;
    }

    public void setChargedUsd(double chargedUsd) {
        this.chargedUsd = chargedUsd;
    }

    public double getPendingUsd() {
        return pendingUsd;
    }

    public void setPendingUsd(double pendingUsd) {
        this.pendingUsd = pendingUsd;
    }

    public Double getCeilingUsd() {
        return ceilingUsd;
    }

    public void setCeilingUsd(Double ceilingUsd) {
        this.ceilingUsd = ceilingUsd;
    }

    public CostPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(CostPolicy policy) {
        this.policy = policy;
    }

    public Double getCapUsd() {
        return capUsd;
    }

    public void setCapUsd(Double capUsd) {
        this.capUsd = capUsd;
    }

    public Double getTenantCeilingUsd() {
        return tenantCeilingUsd;
    }

    public void setTenantCeilingUsd(Double tenantCeilingUsd) {
        this.tenantCeilingUsd = tenantCeilingUsd;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public String getLimitedBy() {
        return limitedBy;
    }

    public void setLimitedBy(String limitedBy) {
        this.limitedBy = limitedBy;
    }
}
